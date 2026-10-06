package com.dozycoffee.ims.product.domain.model

import com.dozycoffee.ims.global.error.DomainValidator
import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.exception.ProductErrorCode
import java.util.Locale

class Product private constructor(
    val productId: Long?,
    val productCode: String,
    val productName: String,
    val category: ProductCategory,
    val unit: String,
    val shelfLifeDays: Int?,
    productStatus: ProductStatus,
) {
    var productStatus: ProductStatus = productStatus
        private set

    /** 상태가 실제로 바뀌었으면 true를 반환한다 */
    fun changeStatus(newStatus: ProductStatus): Boolean {
        if (productStatus == newStatus) return false
        productStatus = newStatus
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Product) return false
        return productId != null && productId == other.productId
    }

    override fun hashCode(): Int = productId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            productCode: String?,
            productName: String?,
            category: ProductCategory?,
            unit: String?,
            shelfLifeDays: Int?,
        ): Product =
            Product(
                productId = null,
                productCode = normalizeProductCode(DomainValidator.requireNotBlank(productCode, ProductErrorCode.INVALID_PRODUCT_CODE)),
                productName = DomainValidator.requireNotBlank(productName, ProductErrorCode.INVALID_PRODUCT_NAME),
                category = DomainValidator.requireNonNull(category, ProductErrorCode.INVALID_CATEGORY),
                unit = DomainValidator.requireNotBlank(unit, ProductErrorCode.INVALID_UNIT),
                shelfLifeDays = validateShelfLifeDays(shelfLifeDays),
                productStatus = ProductStatus.ACTIVE,
            )

        /** 상품 코드는 앞뒤 공백을 없애고 대문자로 통일해 저장한다. 코드로 조회할 때도 같은 규칙을 적용한다 */
        fun normalizeProductCode(productCode: String): String = productCode.trim().uppercase(Locale.ROOT)

        fun reconstitute(
            productId: Long,
            productCode: String,
            productName: String,
            category: ProductCategory,
            unit: String,
            shelfLifeDays: Int?,
            productStatus: ProductStatus,
        ): Product = Product(productId, productCode, productName, category, unit, shelfLifeDays, productStatus)

        private fun validateShelfLifeDays(shelfLifeDays: Int?): Int? {
            if (shelfLifeDays != null && shelfLifeDays < 0) {
                throw InvalidDomainValueException(ProductErrorCode.INVALID_SHELF_LIFE_DAYS)
            }
            return shelfLifeDays
        }
    }
}
