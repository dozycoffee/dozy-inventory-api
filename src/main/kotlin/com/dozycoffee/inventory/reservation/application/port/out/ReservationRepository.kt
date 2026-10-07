package com.dozycoffee.inventory.reservation.application.port.out

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.reservation.domain.model.Reservation

/** 예약과 항목, 할당을 하나의 묶음으로 저장하고 조회한다. 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface ReservationRepository {
    /** 새 예약을 항목, 할당과 함께 저장한다. 같은 멱등 키가 이미 있으면 `DuplicateReservationKeyException`을 던진다 */
    suspend fun save(reservation: Reservation): Reservation

    suspend fun findById(reservationId: Long): Reservation?

    suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): Reservation?
}
