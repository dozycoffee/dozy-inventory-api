package com.dozycoffee.inventory.outbox.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface OutboxEventR2dbcRepository : CoroutineCrudRepository<OutboxEventEntity, Long>
