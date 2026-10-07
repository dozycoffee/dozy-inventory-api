package com.dozycoffee.inventory.product.application.port.`in`.command

import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus

data class ChangeProductStatusCommand(
    val productId: Long,
    val productStatus: ProductStatus,
)
