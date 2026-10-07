package com.dozycoffee.inventory.inventory.application.port.`in`.result

data class AvailabilityResult(
    val products: List<ProductAvailability>,
)

/** 상품 하나의 가용 수량. [totalAvailableQuantity]는 조회한 창고들의 합이다 */
data class ProductAvailability(
    val productId: Long,
    val totalAvailableQuantity: Long,
    val warehouses: List<WarehouseAvailability>,
)

data class WarehouseAvailability(
    val warehouseId: Long,
    val availableQuantity: Long,
)
