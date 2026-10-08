package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

/** `outbox_event`에는 `created_by`가 없어 감사 기반 클래스 없이 `created_at`만 직접 채운다. `payload`는 JSON 컬럼이며 문자열로 주고받는다 */
@Table("outbox_event")
class OutboxEventEntity(
    @Id
    @Column("outbox_event_id")
    var outboxEventId: Long? = null,
    var aggregateType: AggregateType,
    var aggregateId: Long,
    var eventType: String,
    var partitionKey: String,
    var payload: String,
    var status: OutboxStatus,
    var attemptCount: Int,
    @CreatedDate
    var createdAt: LocalDateTime? = null,
    var publishedAt: LocalDateTime? = null,
) {
    fun toDomain(): OutboxEvent =
        OutboxEvent.reconstitute(
            outboxEventId = checkNotNull(outboxEventId) { "저장된 이벤트는 식별자가 있어야 한다" },
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            partitionKey = partitionKey,
            payload = payload,
            status = status,
            attemptCount = attemptCount,
            createdAt = checkNotNull(createdAt) { "저장된 이벤트는 생성 시각이 있어야 한다" },
            publishedAt = publishedAt,
        )

    companion object {
        fun from(event: OutboxEvent): OutboxEventEntity =
            OutboxEventEntity(
                outboxEventId = event.outboxEventId,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                partitionKey = event.partitionKey,
                payload = event.payload,
                status = event.status,
                attemptCount = event.attemptCount,
                createdAt = event.createdAt,
                publishedAt = event.publishedAt,
            )
    }
}
