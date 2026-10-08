package com.dozycoffee.inventory.reservation.adapter.`in`.web.response

import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class ReservationResponse(
    val reservationId: Long,
    val status: ReservationStatus,
    val warehouseId: Long,
    val channel: String,
    val externalOrderId: String,
    val expiresAt: OffsetDateTime?,
    val maxExpiresAt: OffsetDateTime,
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

    companion object {
        /** 서비스의 로컬 시각을 시간대([zone])의 오프셋을 붙여 응답한다 */
        fun from(
            result: ReservationResult,
            zone: ZoneId,
        ): ReservationResponse =
            ReservationResponse(
                reservationId = result.reservationId,
                status = result.status,
                warehouseId = result.warehouseId,
                channel = result.channel,
                externalOrderId = result.externalOrderId,
                expiresAt = result.expiresAt?.let { offsetOf(it, zone) },
                maxExpiresAt = offsetOf(result.maxExpiresAt, zone),
                items =
                    result.items.map { item: ReservationResult.Item ->
                        Item(item.productId, item.requestedQuantity, item.allocations.map { Allocation(it.inventoryId, it.quantity) })
                    },
            )

        private fun offsetOf(
            time: LocalDateTime,
            zone: ZoneId,
        ): OffsetDateTime = time.atZone(zone).toOffsetDateTime()
    }
}
