package com.dozycoffee.inventory.support

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.Properties
import java.util.UUID

/** Kafka를 쓰는 테스트가 공유하는 컨테이너. 처음 쓸 때 한 번 띄우고 JVM이 끝나면 정리된다 */
object KafkaTestContainer {
    private val container: KafkaContainer by lazy { KafkaContainer("apache/kafka:3.8.0").also { it.start() } }

    val bootstrapServers: String get() = container.bootstrapServers

    /** [topics]를 처음부터 읽어 [expected]개가 모일 때까지(최대 [timeout]) 메시지를 모아 반환한다. 모자라면 모인 만큼만 반환한다 */
    fun consume(
        topics: List<String>,
        expected: Int,
        timeout: Duration = Duration.ofSeconds(20),
    ): List<ConsumerRecord<String, String>> {
        val properties: Properties =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "test-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            }
        val records: MutableList<ConsumerRecord<String, String>> = mutableListOf()
        KafkaConsumer<String, String>(properties).use { consumer ->
            consumer.subscribe(topics)
            val deadline: Long = System.nanoTime() + timeout.toNanos()
            while (records.size < expected && System.nanoTime() < deadline) {
                records += consumer.poll(Duration.ofMillis(500))
            }
        }
        return records
    }
}
