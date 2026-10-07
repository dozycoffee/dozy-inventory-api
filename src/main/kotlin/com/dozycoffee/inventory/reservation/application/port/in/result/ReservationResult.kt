package com.dozycoffee.inventory.reservation.application.port.`in`.result

import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import java.time.LocalDateTime

/** 예약의 현재 상태. 같은 멱등 키의 재요청은 저장된 예약에서 다시 만들므로 그 사이 상태가 바뀌었으면 바뀐 상태를 준다 */
data class ReservationResult(
    val reservationId: Long,
    val status: ReservationStatus,
    val warehouseId: Long,
    val channel: String,
    val externalOrderId: String,
    val expiresAt: LocalDateTime?,
    val maxExpiresAt: LocalDateTime,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val requestedQuantity: Int,
        val allocations: List<Allocation>,
    )

    data class Allocation(
        val inventoryId: Long,
        val quantity: Int,
    )
}
