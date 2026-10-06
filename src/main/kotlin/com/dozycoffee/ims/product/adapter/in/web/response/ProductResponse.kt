package com.dozycoffee.ims.product.adapter.`in`.web.response

import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus

data class ProductResponse(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val category: ProductCategory,
    val unit: String,
    val shelfLifeDays: Int?,
    val productStatus: ProductStatus,
) {
    companion object {
        fun from(result: ProductResult): ProductResponse =
            ProductResponse(
                productId = result.productId,
                productCode = result.productCode,
                productName = result.productName,
                category = result.category,
                unit = result.unit,
                shelfLifeDays = result.shelfLifeDays,
                productStatus = result.productStatus,
            )
    }
}
