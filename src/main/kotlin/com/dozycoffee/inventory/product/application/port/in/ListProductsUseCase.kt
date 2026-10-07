package com.dozycoffee.inventory.product.application.port.`in`

import com.dozycoffee.inventory.product.application.port.`in`.command.ListProductsQuery
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductPageResult

interface ListProductsUseCase {
    suspend fun list(query: ListProductsQuery): ProductPageResult
}
