package com.dozycoffee.ims.inventory.adapter.out.persistence

import com.dozycoffee.ims.inventory.domain.enumeration.QualityStatus
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface InventoryR2dbcRepository : CoroutineCrudRepository<InventoryEntity, Long> {
    suspend fun findByWarehouseIdAndLotIdAndQualityStatus(
        warehouseId: Long,
        lotId: Long,
        qualityStatus: QualityStatus,
    ): InventoryEntity?
}
