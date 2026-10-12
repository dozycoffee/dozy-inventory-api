package com.dozycoffee.inventory.reservation.adapter.`in`.web.request

import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class ReallocateReservationRequest(
    @field:NotEmpty @field:Size(max = 1000) @field:Valid val shortages: List<Shortage>,
) {
    data class Shortage(
        @field:Positive val inventoryId: Long,
        @field:Positive val shortageQuantity: Int,
    )

    fun toCommand(
        reservationId: Long,
        idempotencyKey: String,
    ): ReallocateReservationCommand =
        ReallocateReservationCommand(
            reservationId = reservationId,
            shortages = shortages.map { ReallocateReservationCommand.Shortage(it.inventoryId, it.shortageQuantity) },
            idempotencyKey = idempotencyKey,
        )
}
