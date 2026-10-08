package com.dozycoffee.inventory.reservation.application.port.`in`.command

import java.time.LocalDateTime

/** [expiresAt]은 새 만료 시각(절대값)이다 */
data class ExtendReservationCommand(
    val reservationId: Long,
    val expiresAt: LocalDateTime,
)
