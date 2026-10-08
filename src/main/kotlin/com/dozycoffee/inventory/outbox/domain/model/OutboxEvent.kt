package com.dozycoffee.inventory.outbox.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import java.time.LocalDateTime

/**
 * 발행 대기 이벤트. 수량 변경과 같은 트랜잭션으로 저장하고 별도 발행기가 Kafka로 내보낸다(ADR-0025).
 * [payload]는 JSON 문자열이고 같은 [partitionKey]는 [outboxEventId] 순서로 발행한다.
 */
class OutboxEvent private constructor(
    val outboxEventId: Long?,
    val aggregateType: AggregateType,
    val aggregateId: Long,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
    val status: OutboxStatus,
    val attemptCount: Int,
    val createdAt: LocalDateTime?,
    val publishedAt: LocalDateTime?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return outboxEventId != null && outboxEventId == other.outboxEventId
    }

    override fun hashCode(): Int = outboxEventId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        const val MAX_KEY_LENGTH: Int = 100

        fun create(
            aggregateType: AggregateType,
            aggregateId: Long,
            eventType: String?,
            partitionKey: String?,
            payload: String?,
        ): OutboxEvent {
            if (aggregateId < 1 ||
                eventType.isNullOrBlank() ||
                eventType.length > MAX_KEY_LENGTH ||
                partitionKey.isNullOrBlank() ||
                partitionKey.length > MAX_KEY_LENGTH ||
                payload.isNullOrBlank()
            ) {
                throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
            }
            return OutboxEvent(null, aggregateType, aggregateId, eventType, partitionKey, payload, OutboxStatus.PENDING, 0, null, null)
        }

        fun reconstitute(
            outboxEventId: Long,
            aggregateType: AggregateType,
            aggregateId: Long,
            eventType: String,
            partitionKey: String,
            payload: String,
            status: OutboxStatus,
            attemptCount: Int,
            createdAt: LocalDateTime,
            publishedAt: LocalDateTime?,
        ): OutboxEvent =
            OutboxEvent(
                outboxEventId,
                aggregateType,
                aggregateId,
                eventType,
                partitionKey,
                payload,
                status,
                attemptCount,
                createdAt,
                publishedAt,
            )
    }
}
