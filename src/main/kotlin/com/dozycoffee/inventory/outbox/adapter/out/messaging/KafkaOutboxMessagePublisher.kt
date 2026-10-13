package com.dozycoffee.inventory.outbox.adapter.out.messaging

import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * 이벤트를 Kafka로 보낸다(ADR-0025). 토픽은 `{접두사}.{집계 유형 소문자}`이고 메시지 키는 파티션 키, 값은 payload(JSON)이다.
 * 이벤트 ID(UUID)와 유형, 버전, 발행 서비스, 집계 정보, 추적 컨텍스트(있을 때만)는 헤더로 싣고 구독자는 이벤트 ID로 중복 전달을 거른다.
 * 브로커 확인까지 기다린다.
 */
@Component
class KafkaOutboxMessagePublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${inventory.outbox.publisher.topic-prefix:dozy.inventory}") private val topicPrefix: String,
) : OutboxMessagePublisher {
    override suspend fun publish(event: OutboxEvent) {
        val record: ProducerRecord<String, String> =
            ProducerRecord(topicOf(event), null, event.partitionKey, event.payload).apply {
                headers().add(HEADER_EVENT_ID, bytes(event.eventId))
                headers().add(HEADER_EVENT_TYPE, bytes(event.eventType))
                headers().add(HEADER_EVENT_VERSION, bytes(event.eventVersion.toString()))
                headers().add(HEADER_SOURCE, bytes(OutboxEvent.SOURCE))
                headers().add(HEADER_AGGREGATE_TYPE, bytes(event.aggregateType.name))
                headers().add(HEADER_AGGREGATE_ID, bytes(event.aggregateId.toString()))
                event.traceparent?.let { headers().add(HEADER_TRACEPARENT, bytes(it)) }
            }
        kafkaTemplate.send(record).await()
    }

    private fun topicOf(event: OutboxEvent): String = "$topicPrefix.${event.aggregateType.name.lowercase()}"

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val HEADER_EVENT_ID: String = "event-id"
        const val HEADER_EVENT_TYPE: String = "event-type"
        const val HEADER_AGGREGATE_TYPE: String = "aggregate-type"
        const val HEADER_AGGREGATE_ID: String = "aggregate-id"
        const val HEADER_EVENT_VERSION: String = "event-version"
        const val HEADER_SOURCE: String = "source"
        const val HEADER_TRACEPARENT: String = "traceparent"
    }
}
