package com.dozycoffee.inventory.product.adapter.`in`.web.response

import com.dozycoffee.inventory.product.application.port.`in`.result.ProductPageResult

data class ProductPageResponse(
    val items: List<ProductResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    companion object {
        fun from(result: ProductPageResult): ProductPageResponse =
            ProductPageResponse(result.items.map { ProductResponse.from(it) }, result.page, result.size, result.totalElements)
    }
}
