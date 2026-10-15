package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryHistoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.SearchInventoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.SearchInventorySummariesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventorySummariesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventorySummaryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRow
import com.dozycoffee.inventory.inventory.application.port.out.InventorySearchCondition
import com.dozycoffee.inventory.inventory.application.port.out.InventorySummaryRow
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.product.application.port.`in`.GetProductsUseCase
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자용 재고 현황·상품별 요약·상세·이력 조회(ADR-0030, ADR-0043). 모두 읽기 전용이라 락을 걸지 않는다. 요청자의 창고 범위를 적용한다(ADR-0032):
 * 현황과 요약은 범위의 창고로 제한하고, 상세와 이력은 행의 창고가 범위 밖이면 거부한다.
 * 상품 이름 같은 상품 정보는 테이블을 JOIN하지 않고 상품 도메인의 UseCase로 한 번에 조회해 채운다.
 */
@Service
class InventoryQueryService(
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val getProductsUseCase: GetProductsUseCase,
) : SearchInventoriesUseCase,
    SearchInventorySummariesUseCase,
    GetInventoryUseCase,
    GetInventoryHistoriesUseCase {
    @Transactional(readOnly = true)
    override suspend fun search(query: SearchInventoriesQuery): PageResult<InventoryResult> {
        val warehouseIds: Set<Long>? = query.scope.narrow(query.warehouseId?.let(::setOf))
        if (warehouseIds?.isEmpty() == true) return PageResult(emptyList(), query.page, query.size, 0L)
        val condition =
            InventorySearchCondition(warehouseIds, query.productId, query.qualityStatus, query.lotStatus, query.allocationHold)
        val rows: List<InventoryRow> = inventoryRepository.search(condition, offsetOf(query.page, query.size), query.size)
        val products: Map<Long, ProductResult> = productsOf(rows.map { it.productId })
        return PageResult(
            rows.map {
                toResult(it, productOf(products, it.productId))
            },
            query.page,
            query.size,
            inventoryRepository.count(condition),
        )
    }

    @Transactional(readOnly = true)
    override suspend fun searchSummaries(query: SearchInventorySummariesQuery): PageResult<InventorySummaryResult> {
        val warehouseIds: Set<Long>? = query.scope.narrow(query.warehouseId?.let(::setOf))
        if (warehouseIds?.isEmpty() == true) return PageResult(emptyList(), query.page, query.size, 0L)
        val condition =
            InventorySearchCondition(warehouseIds, query.productId, query.qualityStatus, query.lotStatus, query.allocationHold)
        val rows: List<InventorySummaryRow> = inventoryRepository.searchSummaries(condition, offsetOf(query.page, query.size), query.size)
        val products: Map<Long, ProductResult> = productsOf(rows.map { it.productId })
        return PageResult(
            rows.map { toSummaryResult(it, productOf(products, it.productId)) },
            query.page,
            query.size,
            inventoryRepository.countSummaries(condition),
        )
    }

    @Transactional(readOnly = true)
    override suspend fun getById(
        inventoryId: Long,
        scope: WarehouseScope,
    ): InventoryResult {
        val row: InventoryRow = inventoryRepository.findRowById(inventoryId) ?: throw InventoryNotFoundException()
        scope.require(row.warehouseId)
        return toResult(row, productOf(productsOf(listOf(row.productId)), row.productId))
    }

    @Transactional(readOnly = true)
    override suspend fun getHistories(
        inventoryId: Long,
        scope: WarehouseScope,
        page: Int,
        size: Int,
    ): PageResult<InventoryHistoryResult> {
        if (inventoryId < 1 || page < 0 || size !in 1..SearchInventoriesQuery.MAX_SIZE) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_INVENTORY_QUERY)
        }
        val inventory: Inventory = inventoryRepository.findById(inventoryId) ?: throw InventoryNotFoundException()
        scope.require(inventory.warehouseId)
        val items: List<InventoryHistoryResult> =
            inventoryHistoryRepository.findPageByInventoryId(inventoryId, offsetOf(page, size), size).map(InventoryHistoryResult::from)
        return PageResult(items, page, size, inventoryHistoryRepository.countByInventoryId(inventoryId))
    }

    private suspend fun productsOf(productIds: List<Long>): Map<Long, ProductResult> =
        if (productIds.isEmpty()) emptyMap() else getProductsUseCase.getByIds(productIds.toSet())

    // 재고 행은 상품에 외래키로 묶여 있어 상품이 없는 경우는 데이터가 깨진 것이다
    private fun productOf(
        products: Map<Long, ProductResult>,
        productId: Long,
    ): ProductResult = checkNotNull(products[productId]) { "재고 행의 상품을 찾을 수 없다: $productId" }

    private fun offsetOf(
        page: Int,
        size: Int,
    ): Long = page.toLong() * size
}

private fun toSummaryResult(
    row: InventorySummaryRow,
    product: ProductResult,
): InventorySummaryResult =
    InventorySummaryResult(
        productId = row.productId,
        productCode = product.productCode,
        productName = product.productName,
        unit = product.unit,
        warehouseCount = row.warehouseCount,
        rowCount = row.rowCount,
        quantity = row.quantity,
        reservedQuantity = row.reservedQuantity,
        availableQuantity = row.availableQuantity,
        defectiveQuantity = row.defectiveQuantity,
        heldQuantity = row.heldQuantity,
        expiredQuantity = row.expiredQuantity,
        expiringSoonQuantity = row.expiringSoonQuantity,
    )

private fun toResult(
    row: InventoryRow,
    product: ProductResult,
): InventoryResult =
    InventoryResult(
        inventoryId = row.inventoryId,
        warehouseId = row.warehouseId,
        productId = row.productId,
        productCode = product.productCode,
        productName = product.productName,
        unit = product.unit,
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
