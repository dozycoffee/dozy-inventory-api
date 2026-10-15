package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventorySummaryResult

/**
 * 상품별 재고 요약. 수량은 조건에 맞는 재고 행의 합이고 [availableQuantity]는 총 수량에서 예약 수량을 뺀 값이다(품질 상태·보류 미반영).
 * [defectiveQuantity], [heldQuantity], [expiredQuantity], [expiringSoonQuantity]는 해당하는 행의 총 수량이며 서로 겹칠 수 있다
 */
data class InventorySummaryResponse(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unit: String,
    val warehouseCount: Int,
    val rowCount: Int,
    val quantity: Long,
    val reservedQuantity: Long,
    val availableQuantity: Long,
    val defectiveQuantity: Long,
    val heldQuantity: Long,
    val expiredQuantity: Long,
    val expiringSoonQuantity: Long,
) {
    companion object {
        fun from(result: InventorySummaryResult): InventorySummaryResponse =
            InventorySummaryResponse(
                productId = result.productId,
                productCode = result.productCode,
                productName = result.productName,
                unit = result.unit,
                warehouseCount = result.warehouseCount,
                rowCount = result.rowCount,
                quantity = result.quantity,
                reservedQuantity = result.reservedQuantity,
                availableQuantity = result.availableQuantity,
                defectiveQuantity = result.defectiveQuantity,
                heldQuantity = result.heldQuantity,
                expiredQuantity = result.expiredQuantity,
                expiringSoonQuantity = result.expiringSoonQuantity,
            )
    }
}
