package com.dozycoffee.ims.product.application.port.`in`.command

import com.dozycoffee.ims.product.domain.enumeration.ProductCategory

data class RegisterProductCommand(
    val productCode: String,
    val productName: String,
    val category: ProductCategory,
    val unit: String,
    val shelfLifeDays: Int?,
)
