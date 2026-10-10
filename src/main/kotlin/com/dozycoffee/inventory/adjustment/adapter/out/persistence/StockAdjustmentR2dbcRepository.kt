package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface StockAdjustmentR2dbcRepository : CoroutineCrudRepository<StockAdjustmentEntity, Long> {
    suspend fun findByIdempotencyKey(idempotencyKey: String): StockAdjustmentEntity?
}
