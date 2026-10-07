package com.dozycoffee.inventory.product.adapter.out.persistence

import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.product.application.port.out.ProductRepository
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
import com.dozycoffee.inventory.product.domain.model.Product
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.core.awaitCount
import org.springframework.data.r2dbc.core.flow
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Component

@Component
class ProductPersistenceAdapter(
    private val productR2dbcRepository: ProductR2dbcRepository,
    private val r2dbcEntityTemplate: R2dbcEntityTemplate,
) : ProductRepository {
    override suspend fun save(product: Product): Product {
        val entity: ProductEntity = ProductEntity.from(product)
        product.productId?.let { productId: Long ->
            val existing: ProductEntity = productR2dbcRepository.findById(productId) ?: throw ProductNotFoundException()
            entity.copyAuditFieldsFrom(existing)
        }
        return translatingDuplicateKey(duplicate = { DuplicateProductCodeException() }) {
            productR2dbcRepository.save(entity).toDomain()
        }
    }

    override suspend fun findById(productId: Long): Product? = productR2dbcRepository.findById(productId)?.toDomain()

    override suspend fun findByProductCode(productCode: String): Product? =
        productR2dbcRepository.findByProductCode(productCode)?.toDomain()

    override suspend fun existsByProductCode(productCode: String): Boolean = productR2dbcRepository.existsByProductCode(productCode)

    override suspend fun findAll(
        productCode: String?,
        category: ProductCategory?,
        productStatus: ProductStatus?,
        offset: Long,
        limit: Int,
    ): List<Product> {
        val query: Query =
            Query
                .query(criteria(productCode, category, productStatus))
                .sort(Sort.by("productId").ascending())
                .limit(limit)
                .offset(offset)
        return r2dbcEntityTemplate
            .select(ProductEntity::class.java)
            .matching(query)
            .flow()
            .toList()
            .map { it.toDomain() }
    }

    override suspend fun count(
        productCode: String?,
        category: ProductCategory?,
        productStatus: ProductStatus?,
    ): Long =
        r2dbcEntityTemplate
            .select(ProductEntity::class.java)
            .matching(Query.query(criteria(productCode, category, productStatus)))
            .awaitCount()

    private fun criteria(
        productCode: String?,
        category: ProductCategory?,
        productStatus: ProductStatus?,
    ): Criteria =
        listOfNotNull(
            productCode?.let { Criteria.where("productCode").`is`(it) },
            category?.let { Criteria.where("category").`is`(it) },
            productStatus?.let { Criteria.where("productStatus").`is`(it) },
        ).fold(Criteria.empty()) { acc: Criteria, next: Criteria -> acc.and(next) }
}
