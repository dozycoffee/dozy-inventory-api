package com.dozycoffee.inventory.adjustment.application.port.`in`.result

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.global.domain.QualityStatus

/** 조정 결과. 항목은 Lot, 품질 상태 순서이고 [Item.quantityAfter]는 반영 후 총 수량이다 */
data class StockAdjustmentResult(
    val stockAdjustmentId: Long,
    val warehouseId: Long,
    val externalReferenceId: Long,
    val status: AdjustmentStatus,
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
}
