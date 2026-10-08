package com.dozycoffee.inventory.reservation.adapter.`in`.web.request

import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class FulfillReservationRequest(
    @field:NotEmpty @field:Size(max = 1000) @field:Valid val allocations: List<Allocation>,
) {
    data class Allocation(
        @field:Positive val inventoryId: Long,
        @field:PositiveOrZero val shippedQuantity: Int,
    )

    fun toCommand(
        reservationId: Long,
        idempotencyKey: String,
        requesterService: String,
    ): FulfillReservationCommand =
        FulfillReservationCommand(
            reservationId = reservationId,
            allocations = allocations.map { FulfillReservationCommand.Allocation(it.inventoryId, it.shippedQuantity) },
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
