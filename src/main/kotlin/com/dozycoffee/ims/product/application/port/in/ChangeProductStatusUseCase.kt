package com.dozycoffee.ims.product.application.port.`in`

import com.dozycoffee.ims.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult

interface ChangeProductStatusUseCase {
    suspend fun changeStatus(command: ChangeProductStatusCommand): ProductResult
}
