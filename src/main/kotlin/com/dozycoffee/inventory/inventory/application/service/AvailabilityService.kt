package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.GetAvailabilityUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.GetAvailabilityQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AvailabilityResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ProductAvailability
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseAvailability
import com.dozycoffee.inventory.inventory.application.port.out.AvailabilityRow
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AvailabilityService(
    private val inventoryRepository: InventoryRepository,
) : GetAvailabilityUseCase {
    /**
     * 요청한 모든 상품이 결과에 나온다(재고가 없으면 합계 0). 창고를 지정하면 지정한 모든 창고가 나오고(재고 행이 없으면 0),
     * 지정하지 않으면 그 상품의 재고 행이 있는 창고가 나온다. 상품, 창고 모두 ID 오름차순이다.
     */
    @Transactional(readOnly = true)
    override suspend fun getAvailability(query: GetAvailabilityQuery): AvailabilityResult {
        val rows: Map<Long, Map<Long, Long>> =
            inventoryRepository
                .findAvailability(query.productIds, query.warehouseIds)
                .groupBy(AvailabilityRow::productId)
                .mapValues { (_, productRows) -> productRows.associate { it.warehouseId to it.availableQuantity } }

        val products: List<ProductAvailability> =
            query.productIds.sorted().map { productId: Long ->
                val quantities: Map<Long, Long> = rows[productId].orEmpty()
                val warehouseIds: List<Long> = (query.warehouseIds ?: quantities.keys).sorted()
                val warehouses: List<WarehouseAvailability> =
                    warehouseIds.map { WarehouseAvailability(it, quantities[it] ?: 0L) }
                ProductAvailability(productId, warehouses.sumOf { it.availableQuantity }, warehouses)
            }
        return AvailabilityResult(products)
    }
}
