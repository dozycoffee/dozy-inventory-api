package com.dozycoffee.inventory.product.application.port.`in`.result

data class ProductPageResult(
    val items: List<ProductResult>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
