package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult

interface ReleaseReservationUseCase {
    /**
     * 예약을 해제하고 예약 수량을 가용 수량으로 되돌린다. 확정 전·확정된 예약 모두 해제할 수 있다.
     * 이미 해제되었거나 만료된 예약은 바꾸지 않고 현재 상태를 반환하고, 출고 완료된 예약은 409이다.
     */
    suspend fun release(reservationId: Long): ReservationResult
}
