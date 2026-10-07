package com.dozycoffee.ims.inventory.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryHistoryR2dbcRepository : CoroutineCrudRepository<InventoryHistoryEntity, Long> {
    suspend fun findByIdempotencyKeyAndInventoryId(
        idempotencyKey: String,
        inventoryId: Long,
    ): InventoryHistoryEntity?

    fun findAllByIdempotencyKeyOrderByInventoryIdAsc(idempotencyKey: String): Flow<InventoryHistoryEntity>
}
