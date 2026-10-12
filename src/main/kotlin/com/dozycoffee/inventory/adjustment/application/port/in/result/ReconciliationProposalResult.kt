package com.dozycoffee.inventory.adjustment.application.port.`in`.result

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import java.time.LocalDateTime

/**
 * 대사 보정안 하나(불일치한 행 하나). [quantityChange]는 WMS 수량 − inventory 수량이고 승인하면 총 수량이 그만큼 바뀐다.
 * [requiresAdmin]은 변동량이 승인 임계치를 넘어 본사 관리자만 승인할 수 있는지다. [inventoryId]는 반영된 뒤의 대상 재고 행이다.
 */
data class ReconciliationProposalResult(
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
        fun from(
            adjustment: StockAdjustment,
            lot: LotResult,
            requiresAdmin: Boolean,
            inventoryId: Long? = null,
        ): ReconciliationProposalResult {
            val item: StockAdjustmentItem = adjustment.items.single()
            return ReconciliationProposalResult(
                stockAdjustmentId = checkNotNull(adjustment.stockAdjustmentId) { "저장된 보정안은 식별자가 있어야 한다" },
                warehouseId = adjustment.warehouseId,
                reconciliationRunId = adjustment.reconciliationRunId,
                status = adjustment.status,
                productId = lot.productId,
                lotId = lot.lotId,
                lotNumber = lot.lotNumber,
                qualityStatus = item.qualityStatus,
                inventoryQuantity = checkNotNull(item.inventoryQuantity) { "대사 보정안 항목은 inventory 수량이 있어야 한다" },
                wmsQuantity = checkNotNull(item.wmsQuantity) { "대사 보정안 항목은 WMS 수량이 있어야 한다" },
                quantityChange = item.quantityChange,
                requiresAdmin = requiresAdmin,
                inventoryId = inventoryId ?: item.inventoryId,
                approvedBy = adjustment.approvedBy,
                approvedAt = adjustment.approvedAt,
            )
        }
    }
}
