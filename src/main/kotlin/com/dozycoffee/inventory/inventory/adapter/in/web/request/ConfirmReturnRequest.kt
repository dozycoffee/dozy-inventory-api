package com.dozycoffee.inventory.inventory.adapter.`in`.web.request

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class ConfirmReturnRequest(
    @field:Positive val warehouseId: Long,
    @field:Positive val productId: Long,
    @field:NotBlank @field:Size(max = 50) val lotNumber: String,
    val qualityStatus: QualityStatus,
    @field:Positive val quantity: Int,
    @field:Positive val returnItemId: Long,
) {
    fun toCommand(
        idempotencyKey: String,
        requesterService: String,
    ): ConfirmReturnCommand =
        ConfirmReturnCommand(
            warehouseId = warehouseId,
            productId = productId,
            lotNumber = lotNumber,
            qualityStatus = qualityStatus,
            quantity = quantity,
            referenceId = returnItemId,
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
