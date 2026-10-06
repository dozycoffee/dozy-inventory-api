package com.dozycoffee.ims.product.adapter.`in`.web.request

import com.dozycoffee.ims.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus

data class ChangeProductStatusRequest(
    val productStatus: ProductStatus,
) {
    fun toCommand(productId: Long): ChangeProductStatusCommand = ChangeProductStatusCommand(productId, productStatus)
}
