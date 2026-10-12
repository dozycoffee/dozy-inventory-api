package com.dozycoffee.inventory.inventory.adapter.`in`.web.request

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmDisposalCommand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class ConfirmDisposalRequest(
    @field:Positive val warehouseId: Long,
    @field:Positive val productId: Long,
    @field:NotBlank @field:Size(max = 50) val lotNumber: String,
    val qualityStatus: QualityStatus,
    @field:Positive val quantity: Int,
    @field:Positive val disposalItemId: Long,
) {
    fun toCommand(
        idempotencyKey: String,
        requesterService: String,
    ): ConfirmDisposalCommand =
        ConfirmDisposalCommand(
            warehouseId = warehouseId,
            productId = productId,
            lotNumber = lotNumber,
            qualityStatus = qualityStatus,
            quantity = quantity,
            referenceId = disposalItemId,
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
