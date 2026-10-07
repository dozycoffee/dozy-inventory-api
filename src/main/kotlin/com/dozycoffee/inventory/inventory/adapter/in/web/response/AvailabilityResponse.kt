package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.inventory.application.port.`in`.result.AvailabilityResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ProductAvailability
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseAvailability

data class AvailabilityResponse(
    val products: List<ProductAvailabilityResponse>,
) {
    companion object {
        fun from(result: AvailabilityResult): AvailabilityResponse =
            AvailabilityResponse(result.products.map { ProductAvailabilityResponse.from(it) })
    }
}

data class ProductAvailabilityResponse(
    val productId: Long,
    val totalAvailableQuantity: Long,
    val warehouses: List<WarehouseAvailabilityResponse>,
) {
    companion object {
        fun from(product: ProductAvailability): ProductAvailabilityResponse =
            ProductAvailabilityResponse(
                productId = product.productId,
                totalAvailableQuantity = product.totalAvailableQuantity,
                warehouses = product.warehouses.map { WarehouseAvailabilityResponse.from(it) },
            )
    }
}

data class WarehouseAvailabilityResponse(
    val warehouseId: Long,
    val availableQuantity: Long,
) {
    companion object {
        fun from(warehouse: WarehouseAvailability): WarehouseAvailabilityResponse =
            WarehouseAvailabilityResponse(warehouse.warehouseId, warehouse.availableQuantity)
    }
}
