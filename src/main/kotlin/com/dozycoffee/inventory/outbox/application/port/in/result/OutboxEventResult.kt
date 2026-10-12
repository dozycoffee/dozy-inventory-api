package com.dozycoffee.inventory.outbox.application.port.`in`.result

import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import java.time.LocalDateTime

/** 격리 이벤트 조회 결과. [attemptCount]와 [lastError]는 이벤트 고유의 발행 실패만 반영한다(ADR-0037) */
data class OutboxEventResult(
    val outboxEventId: Long,
    val aggregateType: AggregateType,
    val aggregateId: Long,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
    val status: OutboxStatus,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: LocalDateTime?,
    val deadAt: LocalDateTime?,
) {
    companion object {
        fun from(event: OutboxEvent): OutboxEventResult =
            OutboxEventResult(
                outboxEventId = checkNotNull(event.outboxEventId),
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                partitionKey = event.partitionKey,
                payload = event.payload,
                status = event.status,
                attemptCount = event.attemptCount,
                lastError = event.lastError,
                createdAt = event.createdAt,
                deadAt = event.deadAt,
            )
    }
}
