package com.dozycoffee.inventory.outbox.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OutboxEventR2dbcRepository : CoroutineCrudRepository<OutboxEventEntity, Long> {
    @Query("SELECT * FROM outbox_event WHERE status = 'PENDING' ORDER BY outbox_event_id LIMIT :limit")
    fun findPending(limit: Int): Flow<OutboxEventEntity>
}
