package com.dozycoffee.inventory.reservation.adapter.`in`.web.request

import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.time.ZoneId

data class CreateReservationRequest(
    @field:Positive val warehouseId: Long,
    @field:NotBlank @field:Size(max = 50) val channel: String,
    @field:NotBlank @field:Size(max = 100) val externalOrderId: String,
    @field:NotBlank @field:IsoOffsetDateTime val expiresAt: String,
    @field:NotEmpty @field:Size(max = 100) @field:Valid val items: List<Item>,
) {
    data class Item(
        @field:Positive val productId: Long,
        @field:Positive val quantity: Int,
    )

    /** 오프셋이 있는 만료 시각을 서비스의 시간대([zone])의 로컬 시각으로 바꿔 넘긴다 */
    fun toCommand(
        idempotencyKey: String,
        requesterService: String,
        zone: ZoneId,
    ): CreateReservationCommand =
        CreateReservationCommand(
            warehouseId = warehouseId,
            channel = channel,
            externalOrderId = externalOrderId,
            items = items.map { CreateReservationCommand.Item(it.productId, it.quantity) },
            expiresAt = OffsetDateTime.parse(expiresAt).atZoneSameInstant(zone).toLocalDateTime(),
            idempotencyKey = idempotencyKey,
            requesterService = requesterService,
        )
}
