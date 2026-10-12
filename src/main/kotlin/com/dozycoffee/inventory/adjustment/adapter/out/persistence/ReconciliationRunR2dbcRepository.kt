package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ReconciliationRunR2dbcRepository : CoroutineCrudRepository<ReconciliationRunEntity, Long>
