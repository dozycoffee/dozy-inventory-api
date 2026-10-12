package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.domain.QualityStatus
import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryR2dbcRepository : CoroutineCrudRepository<InventoryEntity, Long> {
    fun findAllByLotIdAndQualityStatusOrderByInventoryIdAsc(
        lotId: Long,
        qualityStatus: QualityStatus,
    ): Flow<InventoryEntity>

    suspend fun findByWarehouseIdAndLotIdAndQualityStatus(
        warehouseId: Long,
        lotId: Long,
        qualityStatus: QualityStatus,
    ): InventoryEntity?
}
