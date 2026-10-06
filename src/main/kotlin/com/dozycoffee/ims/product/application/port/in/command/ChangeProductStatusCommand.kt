package com.dozycoffee.ims.product.application.port.`in`.command

import com.dozycoffee.ims.product.domain.enumeration.ProductStatus

data class ChangeProductStatusCommand(
    val productId: Long,
    val productStatus: ProductStatus,
)
