package com.dozycoffee.inventory.outbox.adapter.out.messaging

import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.future.await
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 이벤트를 Kafka로 보낸다(ADR-0025). 토픽은 `{접두사}.{집계 유형 소문자}`이고 메시지 키는 파티션 키, 값은 payload(JSON)이다.
 * 메타데이터는 값(envelope)에만 있고 Kafka 헤더는 쓰지 않는다(ADR-0039). 구독자는 값의 `eventId`로 중복 전달을 거른다.
 * 브로커 확인까지 기다린다.
 */
@Component
class KafkaOutboxMessagePublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    @Value("\${inventory.outbox.publisher.topic-prefix:dozy.inventory}") private val topicPrefix: String,
) : OutboxMessagePublisher {
    override suspend fun publish(event: OutboxEvent) {
        val record: ProducerRecord<String, String> =
            ProducerRecord(topicOf(event), null, event.partitionKey, event.payload)
        kafkaTemplate.send(record).await()
    }

    private fun topicOf(event: OutboxEvent): String = "$topicPrefix.${event.aggregateType.name.lowercase()}"
}
