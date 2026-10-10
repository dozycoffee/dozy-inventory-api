package com.dozycoffee.inventory.inventory.adapter.`in`.web.request

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class ConfirmInboundReceiptRequest(
    @field:Positive val warehouseId: Long,
    @field:Positive val productId: Long,
    @field:Positive val quantity: Int,
    val qualityStatus: QualityStatus,
    @field:NotBlank @field:Size(max = 50) val lotNumber: String,
    val manufactureDate: LocalDate?,
    val expirationDate: LocalDate?,
    @field:Positive val inboundItemId: Long,
) {
    fun toCommand(
        idempotencyKey: String,
        requesterService: String,
    ): ConfirmInboundCommand =
        ConfirmInboundCommand(
            warehouseId = warehouseId,
            productId = productId,
            quantity = quantity,
            qualityStatus = qualityStatus,
            lotNumber = lotNumber,
            manufactureDate = manufactureDate,
            expirationDate = expirationDate,
            referenceId = inboundItemId,
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
