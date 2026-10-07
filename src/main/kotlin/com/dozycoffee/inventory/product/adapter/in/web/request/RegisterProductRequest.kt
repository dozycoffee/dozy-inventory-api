package com.dozycoffee.inventory.product.adapter.`in`.web.request

import com.dozycoffee.inventory.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class RegisterProductRequest(
    @field:NotBlank @field:Size(max = 50) val productCode: String,
    @field:NotBlank @field:Size(max = 100) val productName: String,
    val category: ProductCategory,
    @field:NotBlank @field:Size(max = 20) val unit: String,
    @field:PositiveOrZero val shelfLifeDays: Int?,
) {
    fun toCommand(): RegisterProductCommand = RegisterProductCommand(productCode, productName, category, unit, shelfLifeDays)
}
