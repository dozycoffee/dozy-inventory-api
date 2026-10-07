package com.dozycoffee.inventory.product.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ProductR2dbcRepository : CoroutineCrudRepository<ProductEntity, Long> {
    suspend fun findByProductCode(productCode: String): ProductEntity?

    suspend fun existsByProductCode(productCode: String): Boolean
}
