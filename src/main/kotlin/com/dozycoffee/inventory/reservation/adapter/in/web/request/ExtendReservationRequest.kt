package com.dozycoffee.inventory.reservation.adapter.`in`.web.request

import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import jakarta.validation.constraints.NotBlank
import java.time.OffsetDateTime
import java.time.ZoneId

data class ExtendReservationRequest(
    @field:NotBlank @field:IsoOffsetDateTime val expiresAt: String,
) {
    /** 오프셋이 있는 새 만료 시각을 서비스의 시간대([zone])의 로컬 시각으로 바꿔 넘긴다 */
    fun toCommand(
        reservationId: Long,
        zone: ZoneId,
    ): ExtendReservationCommand =
        ExtendReservationCommand(reservationId, OffsetDateTime.parse(expiresAt).atZoneSameInstant(zone).toLocalDateTime())
}
