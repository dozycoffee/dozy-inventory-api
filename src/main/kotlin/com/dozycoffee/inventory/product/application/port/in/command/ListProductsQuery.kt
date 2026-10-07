package com.dozycoffee.inventory.product.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.ProductErrorCode

data class ListProductsQuery(
    val productCode: String?,
    val category: ProductCategory?,
    val productStatus: ProductStatus?,
    val page: Int,
    val size: Int,
) {
    init {
        if (page < 0 || size !in 1..MAX_SIZE) {
            throw InvalidDomainValueException(ProductErrorCode.INVALID_PAGE_REQUEST)
        }
    }

    val offset: Long
        get() = page.toLong() * size

    companion object {
        const val MAX_SIZE: Int = 100
    }
}
