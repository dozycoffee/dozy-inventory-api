package com.dozycoffee.inventory.inventory.application.port.out

/**
 * 상품 하나의 재고를 조건에 맞는 재고 행들로 합친 조회용 행. 수량은 모두 행 자체의 합이며 [availableQuantity]는 총 수량에서 예약 수량을 뺀 값이다.
 * [defectiveQuantity], [heldQuantity], [expiredQuantity], [expiringSoonQuantity]는 각각 품질 상태가 불량인 행, 할당 보류 중인 행,
 * Lot이 유통기한 만료·임박인 행의 총 수량이고 서로 겹칠 수 있다.
 */
data class InventorySummaryRow(
    val productId: Long,
    val warehouseCount: Int,
    val rowCount: Int,
    val quantity: Long,
    val reservedQuantity: Long,
    val defectiveQuantity: Long,
    val heldQuantity: Long,
    val expiredQuantity: Long,
    val expiringSoonQuantity: Long,
) {
    val availableQuantity: Long
        get() = quantity - reservedQuantity
}
