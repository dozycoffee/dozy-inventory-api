package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult

interface ExtendReservationUseCase {
    /**
     * 확정 전 예약의 만료 시각을 늘린다. 현재 만료 시각 이상이고 최대 만료 시각(생성 시각 + 채널 상한) 이하여야 한다.
     * 같은 만료 시각이면 바꾸지 않고 현재 상태를 반환하고, 만료 시각이 지났거나 확정 전이 아닌 예약은 409이다.
     */
    suspend fun extend(command: ExtendReservationCommand): ReservationResult
}
