package com.dozycoffee.inventory.reservation.adapter.`in`.web.response

import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import java.time.LocalDate

data class ReallocationResponse(
    val reservationId: Long,
    val status: ReservationStatus,
    val heldInventoryIds: List<Long>,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val allocations: List<Allocation>,
    )

    data class Allocation(
        val inventoryId: Long,
        val lotId: Long,
        val lotNumber: String,
        val expirationDate: LocalDate?,
        val quantity: Int,
    )

    companion object {
        fun from(result: ReallocationResult): ReallocationResponse =
            ReallocationResponse(
                reservationId = result.reservationId,
                status = result.status,
                heldInventoryIds = result.heldInventoryIds,
                items =
                    result.items.map { item ->
                        Item(
                            item.productId,
                            item.allocations.map { Allocation(it.inventoryId, it.lotId, it.lotNumber, it.expirationDate, it.quantity) },
                        )
                    },
            )
    }
}
