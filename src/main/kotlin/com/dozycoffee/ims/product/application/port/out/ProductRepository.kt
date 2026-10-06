package com.dozycoffee.ims.product.application.port.out

import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.model.Product

interface ProductRepository {
    suspend fun save(product: Product): Product

    suspend fun findById(productId: Long): Product?

    suspend fun findByProductCode(productCode: String): Product?

    suspend fun existsByProductCode(productCode: String): Boolean

    /** `product_id` 오름차순으로 정렬해 반환한다. 필터가 null이면 해당 조건을 적용하지 않는다 */
    suspend fun findAll(
        category: ProductCategory?,
        productStatus: ProductStatus?,
        offset: Long,
        limit: Int,
    ): List<Product>

    suspend fun count(
        category: ProductCategory?,
        productStatus: ProductStatus?,
    ): Long
}
