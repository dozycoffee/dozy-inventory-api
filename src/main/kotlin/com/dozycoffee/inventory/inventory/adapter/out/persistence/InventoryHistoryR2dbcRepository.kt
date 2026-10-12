package com.dozycoffee.inventory.inventory.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryHistoryR2dbcRepository : CoroutineCrudRepository<InventoryHistoryEntity, Long> {
    suspend fun findByIdempotencyKeyAndInventoryId(
        idempotencyKey: String,
        inventoryId: Long,
    ): InventoryHistoryEntity?

    fun findAllByIdempotencyKeyOrderByInventoryIdAsc(idempotencyKey: String): Flow<InventoryHistoryEntity>

    @Query(
        "SELECT * FROM inventory_history WHERE inventory_id = :inventoryId " +
            "ORDER BY created_at DESC, inventory_history_id DESC LIMIT :limit OFFSET :offset",
    )
    fun findPage(
        inventoryId: Long,
        offset: Long,
        limit: Int,
    ): Flow<InventoryHistoryEntity>

    suspend fun countByInventoryId(inventoryId: Long): Long
}
