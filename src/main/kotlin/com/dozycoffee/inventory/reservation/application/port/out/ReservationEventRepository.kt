package com.dozycoffee.inventory.reservation.application.port.out

import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent

interface ReservationEventRepository {
    /** 이력은 바꿀 수 없어 새로 저장만 한다 */
    suspend fun save(event: ReservationEvent): ReservationEvent

    suspend fun findAllByReservationId(reservationId: Long): List<ReservationEvent>
}
