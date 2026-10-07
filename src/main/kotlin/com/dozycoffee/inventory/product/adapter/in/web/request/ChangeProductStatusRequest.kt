package com.dozycoffee.inventory.product.adapter.`in`.web.request

import com.dozycoffee.inventory.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus

data class ChangeProductStatusRequest(
    val productStatus: ProductStatus,
) {
    fun toCommand(productId: Long): ChangeProductStatusCommand = ChangeProductStatusCommand(productId, productStatus)
}
