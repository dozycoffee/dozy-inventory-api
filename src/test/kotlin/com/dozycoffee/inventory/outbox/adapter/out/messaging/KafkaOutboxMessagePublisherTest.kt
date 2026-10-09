package com.dozycoffee.inventory.outbox.adapter.out.messaging

import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import com.dozycoffee.inventory.support.KafkaTestContainer
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import java.time.LocalDateTime
import java.util.UUID

/** 실제 Kafka(Testcontainers)로 토픽, 키, 값, 헤더와 브로커 확인 대기, 실패 전파를 검증한다 */
class KafkaOutboxMessagePublisherTest {
    private val factories: MutableList<DefaultKafkaProducerFactory<String, String>> = mutableListOf()
    private val prefix: String = "test-${UUID.randomUUID().toString().take(8)}"

    @AfterEach
    fun cleanUp() {
        factories.forEach { it.destroy() }
    }

    private fun publisher(
        bootstrapServers: String = KafkaTestContainer.bootstrapServers,
        blockMs: Int = 20_000,
    ): KafkaOutboxMessagePublisher {
        val factory: DefaultKafkaProducerFactory<String, String> =
            DefaultKafkaProducerFactory(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to blockMs,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to blockMs,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to blockMs * 2,
                ),
            )
        factories += factory
        return KafkaOutboxMessagePublisher(KafkaTemplate(factory), prefix)
    }

    private fun event(
        id: Long,
        type: AggregateType = AggregateType.RESERVATION,
        key: String = "10:7",
        payload: String = """{"eventType":"RESERVATION_CREATED","reservationId":7}""",
    ): OutboxEvent =
        OutboxEvent.reconstitute(
            id,
            type,
            7L,
            "RESERVATION_CREATED",
            key,
            payload,
            OutboxStatus.PENDING,
            0,
            LocalDateTime.of(2026, 10, 8, 9, 0),
            null,
        )

    private fun header(
        record: ConsumerRecord<String, String>,
        name: String,
    ): String = String(record.headers().lastHeader(name).value(), Charsets.UTF_8)

    @Nested
    inner class `발행` {
        @Test
        fun `집계 유형별 토픽에 파티션 키와 payload를 보내고 이벤트 정보를 헤더로 싣는다`() =
            runBlocking<Unit> {
                publisher().publish(event(42L))

                val records: List<ConsumerRecord<String, String>> = KafkaTestContainer.consume(listOf("$prefix.reservation"), 1)
                assertThat(records).hasSize(1)
                val record: ConsumerRecord<String, String> = records.single()
                assertThat(record.topic()).isEqualTo("$prefix.reservation")
                assertThat(record.key()).isEqualTo("10:7")
                assertThat(record.value()).isEqualTo("""{"eventType":"RESERVATION_CREATED","reservationId":7}""")
                assertThat(header(record, KafkaOutboxMessagePublisher.HEADER_EVENT_ID)).isEqualTo("42")
                assertThat(header(record, KafkaOutboxMessagePublisher.HEADER_EVENT_TYPE)).isEqualTo("RESERVATION_CREATED")
                assertThat(header(record, KafkaOutboxMessagePublisher.HEADER_AGGREGATE_TYPE)).isEqualTo("RESERVATION")
                assertThat(header(record, KafkaOutboxMessagePublisher.HEADER_AGGREGATE_ID)).isEqualTo("7")
            }

        @Test
        fun `집계 유형마다 다른 토픽으로 나간다`() =
            runBlocking<Unit> {
                val publisher: KafkaOutboxMessagePublisher = publisher()
                publisher.publish(event(1L, AggregateType.INVENTORY, "10:100", """{"a":1}"""))
                publisher.publish(event(2L, AggregateType.PRODUCT, "100", """{"b":2}"""))

                val records: List<ConsumerRecord<String, String>> =
                    KafkaTestContainer.consume(listOf("$prefix.inventory", "$prefix.product"), 2)
                assertThat(records.map { it.topic() }).containsExactlyInAnyOrder("$prefix.inventory", "$prefix.product")
            }

        @Test
        fun `같은 키의 메시지는 보낸 순서대로 읽힌다`() =
            runBlocking<Unit> {
                val publisher: KafkaOutboxMessagePublisher = publisher()
                (1L..20L).forEach { publisher.publish(event(it, payload = """{"seq":$it}""")) }

                val records: List<ConsumerRecord<String, String>> = KafkaTestContainer.consume(listOf("$prefix.reservation"), 20)
                assertThat(records.map { header(it, KafkaOutboxMessagePublisher.HEADER_EVENT_ID).toLong() }).isEqualTo((1L..20L).toList())
            }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `브로커에 닿지 않으면 시간이 지난 뒤 예외로 알린다`() =
            runBlocking<Unit> {
                assertThrows<Exception> { publisher(bootstrapServers = "localhost:1", blockMs = 1000).publish(event(1L)) }
            }
    }
}
