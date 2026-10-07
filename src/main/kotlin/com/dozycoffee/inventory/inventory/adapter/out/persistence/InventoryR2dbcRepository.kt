package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryR2dbcRepository : CoroutineCrudRepository<InventoryEntity, Long> {
    suspend fun findByWarehouseIdAndLotIdAndQualityStatus(
        warehouseId: Long,
        lotId: Long,
        qualityStatus: QualityStatus,
    ): InventoryEntity?
}
