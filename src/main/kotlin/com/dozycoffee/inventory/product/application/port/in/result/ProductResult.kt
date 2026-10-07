package com.dozycoffee.inventory.product.application.port.`in`.result

import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.model.Product

data class ProductResult(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val category: ProductCategory,
    val unit: String,
    val shelfLifeDays: Int?,
    val productStatus: ProductStatus,
) {
    /** 판매 중인(`ACTIVE`) 상품인지. 다른 도메인이 상태 열거형을 알 필요 없게 한다 */
    val isActive: Boolean
        get() = productStatus == ProductStatus.ACTIVE

    companion object {
        fun from(product: Product): ProductResult =
            ProductResult(
                productId = checkNotNull(product.productId) { "저장되지 않은 상품은 결과로 변환할 수 없다" },
                productCode = product.productCode,
                productName = product.productName,
                category = product.category,
                unit = product.unit,
                shelfLifeDays = product.shelfLifeDays,
                productStatus = product.productStatus,
            )
    }
}
