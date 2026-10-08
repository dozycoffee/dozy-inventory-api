package com.dozycoffee.inventory.reservation.application.port.out

import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem

/**
 * 예약이 바뀌었음을 구독자(OMS, WMS)에게 알리는 이벤트. 예약 수량이 바뀌면 가용 재고도 바뀐다.
 * [Item.allocations]는 수량이 할당된 재고 행별 내역이며 WMS가 출고를 지시할 때 쓴다.
 * [idempotencyKey]는 예약을 만든 요청의 멱등 키다.
 */
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
        val allocations: List<Allocation>,
    )

    data class Allocation(
        val inventoryId: Long,
        val quantity: Int,
    )

    companion object {
        fun of(
            changeType: ReservationChangeType,
            reservation: Reservation,
        ): ReservationChangedEvent =
            ReservationChangedEvent(
                changeType = changeType,
                reservationId = checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" },
                warehouseId = reservation.warehouseId,
                channel = reservation.channel.value,
                externalOrderId = reservation.externalOrderId.value,
                items =
                    reservation.items.map { item: ReservationItem ->
                        Item(item.productId, item.requestedQuantity, item.allocations.map { Allocation(it.inventoryId, it.quantity) })
                    },
                idempotencyKey = reservation.idempotencyKey.value,
            )
    }
}

enum class ReservationChangeType {
    CREATED,
    CONFIRMED,
    EXTENDED,
    RELEASED,
    EXPIRED,
    FULFILLED,
}
