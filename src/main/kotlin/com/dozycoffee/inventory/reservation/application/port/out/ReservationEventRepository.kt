package com.dozycoffee.inventory.reservation.application.port.out

import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent

interface ReservationEventRepository {
    /**
     * 이력은 바꿀 수 없어 새로 저장만 한다. 같은 예약에 같은 멱등 키의 이력이 이미 있으면
     * [com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException]이다.
     */
    suspend fun save(event: ReservationEvent): ReservationEvent

    /** 예약에서 [idempotencyKey]로 처리한 이력. 없으면 null */
    suspend fun findByIdempotencyKey(
        reservationId: Long,
        idempotencyKey: String,
    ): ReservationEvent?

    suspend fun findAllByReservationId(reservationId: Long): List<ReservationEvent>
}
