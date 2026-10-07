package com.dozycoffee.inventory.product.application.port.`in`

import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult

interface GetProductUseCase {
    suspend fun getById(productId: Long): ProductResult

    suspend fun getByCode(productCode: String): ProductResult
}
