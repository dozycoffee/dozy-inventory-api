package com.dozycoffee.inventory.product.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.ProductErrorCode

data class ListProductsQuery(
    val productCode: String?,
    val keyword: String?,
    val category: ProductCategory?,
    val productStatus: ProductStatus?,
    val page: Int,
    val size: Int,
) {
    init {
        if (page < 0 || size !in 1..MAX_SIZE) {
            throw InvalidDomainValueException(ProductErrorCode.INVALID_PAGE_REQUEST)
        }
        if ((keyword?.trim()?.length ?: 0) > MAX_KEYWORD_LENGTH) {
            throw InvalidDomainValueException(ProductErrorCode.INVALID_KEYWORD)
        }
    }

    val offset: Long
        get() = page.toLong() * size

    companion object {
        const val MAX_SIZE: Int = 100
        const val MAX_KEYWORD_LENGTH: Int = 100
    }
}
