package com.dozycoffee.inventory.reservation.adapter.`in`.web.response

import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus

data class FulfillmentResponse(
    val reservationId: Long,
    val status: ReservationStatus,
    val allocations: List<Allocation>,
) {
    data class Allocation(
        val productId: Long,
        val inventoryId: Long,
        val allocatedQuantity: Int,
        val shippedQuantity: Int,
        val shortageQuantity: Int,
        val quantityAfter: Int?,
    )

    companion object {
        fun from(result: FulfillmentResult): FulfillmentResponse =
            FulfillmentResponse(
                reservationId = result.reservationId,
                status = result.status,
                allocations =
                    result.allocations.map {
                        Allocation(
                            it.productId,
                            it.inventoryId,
                            it.allocatedQuantity,
                            it.shippedQuantity,
                            it.shortageQuantity,
                            it.quantityAfter,
                        )
                    },
            )
    }
}
