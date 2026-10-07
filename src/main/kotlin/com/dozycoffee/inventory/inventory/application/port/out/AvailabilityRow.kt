package com.dozycoffee.inventory.inventory.application.port.out

/** 창고와 상품별 가용 수량. 정상 품질이고 할당 보류가 아닌 행의 (총 수량 − 예약 수량) 합이다 */
data class AvailabilityRow(
    val warehouseId: Long,
    val productId: Long,
    val availableQuantity: Long,
)
