package com.dozycoffee.inventory.product.application.port.`in`

import com.dozycoffee.inventory.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult

interface ChangeProductStatusUseCase {
    suspend fun changeStatus(command: ChangeProductStatusCommand): ProductResult
}
