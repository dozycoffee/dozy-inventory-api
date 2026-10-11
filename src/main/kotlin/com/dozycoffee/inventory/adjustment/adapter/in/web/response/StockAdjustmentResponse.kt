package com.dozycoffee.inventory.adjustment.adapter.`in`.web.response

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult
import com.dozycoffee.inventory.global.domain.QualityStatus

/** 항목은 Lot, 품질 상태 순서다. [Item.quantityAfter]는 반영 직후의 총 수량이며 재요청에도 처음과 같다 */
data class StockAdjustmentResponse(
    val stockAdjustmentId: Long,
    val warehouseId: Long,
    val auditId: Long,
    val status: String,
    val approvedBy: String?,
    val items: List<Item>,
) {
    data class Item(
        val stockAdjustmentItemId: Long,
        val inventoryId: Long,
        val lotId: Long,
        val qualityStatus: QualityStatus,
        val quantityChange: Int,
        val quantityAfter: Int,
    )

    companion object {
        fun from(result: StockAdjustmentResult): StockAdjustmentResponse =
            StockAdjustmentResponse(
                stockAdjustmentId = result.stockAdjustmentId,
                warehouseId = result.warehouseId,
                auditId = result.externalReferenceId,
                status = result.status.name,
                approvedBy = result.approvedBy,
                items =
                    result.items.map {
                        Item(it.stockAdjustmentItemId, it.inventoryId, it.lotId, it.qualityStatus, it.quantityChange, it.quantityAfter)
                    },
            )
    }
}
