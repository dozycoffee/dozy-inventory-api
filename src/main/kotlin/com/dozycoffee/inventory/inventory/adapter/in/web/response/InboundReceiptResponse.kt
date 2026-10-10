package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult

data class InboundReceiptResponse(
    val inventoryId: Long,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    val quantityChange: Int,
    val quantityAfter: Int,
    val inventoryHistoryId: Long,
) {
    companion object {
        fun from(result: InboundResult): InboundReceiptResponse =
            InboundReceiptResponse(
                inventoryId = result.inventoryId,
                warehouseId = result.warehouseId,
                productId = result.productId,
                lotId = result.lotId,
                qualityStatus = result.qualityStatus,
                quantityChange = result.quantityChange,
                quantityAfter = result.quantityAfter,
                inventoryHistoryId = result.inventoryHistoryId,
            )
    }
}
