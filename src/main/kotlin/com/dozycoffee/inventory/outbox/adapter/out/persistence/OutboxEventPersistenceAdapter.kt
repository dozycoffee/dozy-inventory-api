package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.springframework.stereotype.Component

@Component
class OutboxEventPersistenceAdapter(
    private val outboxEventR2dbcRepository: OutboxEventR2dbcRepository,
) : OutboxEventRepository {
    override suspend fun save(event: OutboxEvent): OutboxEvent {
        check(event.outboxEventId == null) { "이벤트는 새로 저장만 할 수 있다" }
        return outboxEventR2dbcRepository.save(OutboxEventEntity.from(event)).toDomain()
    }

    override suspend fun findById(outboxEventId: Long): OutboxEvent? = outboxEventR2dbcRepository.findById(outboxEventId)?.toDomain()
}
