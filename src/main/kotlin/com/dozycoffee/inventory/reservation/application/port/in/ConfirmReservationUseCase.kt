package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult

interface ConfirmReservationUseCase {
    /**
     * 확정 전 예약을 확정한다. 확정된 예약은 만료되지 않고 WMS 출고 지시의 대상이 된다.
     * 이미 확정된 예약은 바꾸지 않고 현재 상태를 반환하고, 만료 시각이 지났거나 해제·만료·출고 완료된 예약은 409이다.
     */
    suspend fun confirm(reservationId: Long): ReservationResult
}
