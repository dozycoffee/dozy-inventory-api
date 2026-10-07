package com.dozycoffee.inventory.product.application.port.`in`

import com.dozycoffee.inventory.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult

interface RegisterProductUseCase {
    suspend fun register(command: RegisterProductCommand): ProductResult
}
