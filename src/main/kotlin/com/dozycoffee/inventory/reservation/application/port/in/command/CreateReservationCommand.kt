package com.dozycoffee.inventory.reservation.application.port.`in`.command

import java.time.LocalDateTime

/**
 * 한 주문의 예약 요청. 호출 서비스가 확정한 창고 하나에서 상품별 수량을 잡는다. [expiresAt]은 요청자가 정하며 채널의 상한을 넘을 수 없다.
 * [requesterService]는 요청 주체(system client의 principalId)다.
 */
data class CreateReservationCommand(
    val warehouseId: Long,
    val channel: String,
    val externalOrderId: String,
    val items: List<Item>,
    val expiresAt: LocalDateTime,
    val idempotencyKey: String,
    val requesterService: String,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )
}
