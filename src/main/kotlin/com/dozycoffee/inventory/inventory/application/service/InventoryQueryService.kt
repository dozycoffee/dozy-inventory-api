package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryHistoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.SearchInventoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRow
import com.dozycoffee.inventory.inventory.application.port.out.InventorySearchCondition
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 관리자용 재고 현황·상세·이력 조회(ADR-0030). 모두 읽기 전용이라 락을 걸지 않는다 */
@Service
class InventoryQueryService(
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
) : SearchInventoriesUseCase,
    GetInventoryUseCase,
    GetInventoryHistoriesUseCase {
    @Transactional(readOnly = true)
    override suspend fun search(query: SearchInventoriesQuery): PageResult<InventoryResult> {
        val condition =
            InventorySearchCondition(query.warehouseId, query.productId, query.qualityStatus, query.lotStatus, query.allocationHold)
        val items: List<InventoryResult> =
            inventoryRepository.search(condition, offsetOf(query.page, query.size), query.size).map(::toResult)
        return PageResult(items, query.page, query.size, inventoryRepository.count(condition))
    }

    @Transactional(readOnly = true)
    override suspend fun getById(inventoryId: Long): InventoryResult =
        toResult(inventoryRepository.findRowById(inventoryId) ?: throw InventoryNotFoundException())

    @Transactional(readOnly = true)
    override suspend fun getHistories(
        inventoryId: Long,
        page: Int,
        size: Int,
    ): PageResult<InventoryHistoryResult> {
        if (inventoryId < 1 || page < 0 || size !in 1..SearchInventoriesQuery.MAX_SIZE) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_INVENTORY_QUERY)
        }
        if (inventoryRepository.findById(inventoryId) == null) throw InventoryNotFoundException()
        val items: List<InventoryHistoryResult> =
            inventoryHistoryRepository.findPageByInventoryId(inventoryId, offsetOf(page, size), size).map(InventoryHistoryResult::from)
        return PageResult(items, page, size, inventoryHistoryRepository.countByInventoryId(inventoryId))
    }

    private fun offsetOf(
        page: Int,
        size: Int,
    ): Long = page.toLong() * size
}

private fun toResult(row: InventoryRow): InventoryResult =
    InventoryResult(
        inventoryId = row.inventoryId,
        warehouseId = row.warehouseId,
        productId = row.productId,
        lotId = row.lotId,
        lotNumber = row.lotNumber,
        manufactureDate = row.manufactureDate,
        expirationDate = row.expirationDate,
        lotStatus = row.lotStatus,
        qualityStatus = row.qualityStatus,
        quantity = row.quantity,
        reservedQuantity = row.reservedQuantity,
        availableQuantity = row.availableQuantity,
        allocationHold = row.allocationHold,
        holdReason = row.holdReason,
        heldAt = row.heldAt,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
    )
