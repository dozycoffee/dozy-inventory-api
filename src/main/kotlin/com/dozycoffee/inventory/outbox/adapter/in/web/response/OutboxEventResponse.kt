package com.dozycoffee.inventory.outbox.adapter.`in`.web.response

import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import java.time.LocalDateTime

data class OutboxEventResponse(
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
        fun from(result: OutboxEventResult): OutboxEventResponse =
            OutboxEventResponse(
                outboxEventId = result.outboxEventId,
                aggregateType = result.aggregateType,
                aggregateId = result.aggregateId,
                eventType = result.eventType,
                partitionKey = result.partitionKey,
                payload = result.payload,
                status = result.status,
                attemptCount = result.attemptCount,
                lastError = result.lastError,
                createdAt = result.createdAt,
                deadAt = result.deadAt,
            )
    }
}
