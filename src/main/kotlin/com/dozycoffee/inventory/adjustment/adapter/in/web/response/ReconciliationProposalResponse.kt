package com.dozycoffee.inventory.adjustment.adapter.`in`.web.response

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.global.domain.QualityStatus
import java.time.LocalDateTime

data class ReconciliationProposalResponse(
    val stockAdjustmentId: Long,
    val warehouseId: Long,
    val reconciliationRunId: Long?,
    val status: AdjustmentStatus,
    val productId: Long,
    val lotId: Long,
    val lotNumber: String,
    val qualityStatus: QualityStatus,
    val inventoryQuantity: Int,
    val wmsQuantity: Int,
    val quantityChange: Int,
    val requiresAdmin: Boolean,
    val inventoryId: Long?,
    val approvedBy: String?,
    val approvedAt: LocalDateTime?,
) {
    companion object {
        fun from(result: ReconciliationProposalResult): ReconciliationProposalResponse =
            ReconciliationProposalResponse(
                stockAdjustmentId = result.stockAdjustmentId,
                warehouseId = result.warehouseId,
                reconciliationRunId = result.reconciliationRunId,
                status = result.status,
                productId = result.productId,
                lotId = result.lotId,
                lotNumber = result.lotNumber,
                qualityStatus = result.qualityStatus,
                inventoryQuantity = result.inventoryQuantity,
                wmsQuantity = result.wmsQuantity,
                quantityChange = result.quantityChange,
                requiresAdmin = result.requiresAdmin,
                inventoryId = result.inventoryId,
                approvedBy = result.approvedBy,
                approvedAt = result.approvedAt,
            )
    }
}
