package com.dozycoffee.inventory.reservation.application.port.out

/** 예약이 바뀌었음을 구독자(OMS, WMS)에게 알리는 이벤트. 예약 수량이 바뀌면 가용 재고도 바뀐다 */
data class ReservationChangedEvent(
    val changeType: ReservationChangeType,
    val reservationId: Long,
    val warehouseId: Long,
    val channel: String,
    val externalOrderId: String,
    val items: List<Item>,
    val idempotencyKey: String,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}

enum class ReservationChangeType {
    CREATED,
}
