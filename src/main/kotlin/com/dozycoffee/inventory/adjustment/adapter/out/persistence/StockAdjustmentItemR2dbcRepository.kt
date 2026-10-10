package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface StockAdjustmentItemR2dbcRepository : CoroutineCrudRepository<StockAdjustmentItemEntity, Long> {
    fun findAllByStockAdjustmentIdOrderByStockAdjustmentItemIdAsc(stockAdjustmentId: Long): Flow<StockAdjustmentItemEntity>
}
