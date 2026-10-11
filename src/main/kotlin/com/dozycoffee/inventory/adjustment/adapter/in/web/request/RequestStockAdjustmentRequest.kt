package com.dozycoffee.inventory.adjustment.adapter.`in`.web.request

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.global.domain.QualityStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/** WMS의 실사 조정 요청. [auditId]는 WMS의 실사 건 ID이고 [approvedBy]는 승인이 필요한 변동량이 있을 때 보내는 승인자다 */
data class RequestStockAdjustmentRequest(
    @field:Positive val warehouseId: Long,
    @field:Positive val auditId: Long,
    @field:Size(max = 100) val approvedBy: String?,
    @field:NotEmpty @field:Size(max = RequestStockAdjustmentCommand.MAX_ITEMS) @field:Valid val items: List<Item>,
) {
    data class Item(
        @field:Positive val productId: Long,
        @field:NotBlank @field:Size(max = RequestStockAdjustmentCommand.MAX_LOT_NUMBER_LENGTH) val lotNumber: String,
        val qualityStatus: QualityStatus,
        val quantityChange: Int,
    )

    fun toCommand(
        idempotencyKey: String,
        requesterService: String,
    ): RequestStockAdjustmentCommand =
        RequestStockAdjustmentCommand(
            warehouseId = warehouseId,
            externalReferenceId = auditId,
            items = items.map { RequestStockAdjustmentCommand.Item(it.productId, it.lotNumber, it.qualityStatus, it.quantityChange) },
            approvedBy = approvedBy,
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
