package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.FindWarehouseIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetWarehouseQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 대사(ADR-0033)가 쓰는 읽기 전용 조회. 락을 걸지 않는다 */
@Service
class WarehouseQuantityService(
    private val inventoryRepository: InventoryRepository,
) : GetWarehouseQuantitiesUseCase,
    FindWarehouseIdsUseCase {
    @Transactional(readOnly = true)
    override suspend fun getQuantities(
        warehouseId: Long,
        productId: Long?,
        lotNumber: String?,
    ): List<WarehouseQuantityResult> =
        inventoryRepository.findRowsByWarehouse(warehouseId, productId, lotNumber).map { row: InventoryRow ->
            WarehouseQuantityResult(row.inventoryId, row.productId, row.lotId, row.lotNumber, row.qualityStatus, row.quantity)
        }

    @Transactional(readOnly = true)
    override suspend fun findWarehouseIds(): List<Long> = inventoryRepository.findWarehouseIds()
}
