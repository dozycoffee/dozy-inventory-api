package com.dozycoffee.inventory.inventory.application.port.`in`.result

/**
 * 관리자가 보는 상품별 재고 요약. 조건에 맞는 재고 행을 상품 단위로 합친 값이다. [availableQuantity]는 총 수량에서 예약 수량을 뺀 값이며
 * 품질 상태와 보류는 반영하지 않는다. [defectiveQuantity], [heldQuantity], [expiredQuantity], [expiringSoonQuantity]는 해당하는 행의 총 수량이고 서로 겹칠 수 있다
 */
data class InventorySummaryResult(
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
)
