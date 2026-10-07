package com.dozycoffee.inventory.reservation.application.port.out

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import java.time.LocalDateTime

/** 예약과 항목, 할당을 하나의 묶음으로 저장하고 조회한다. 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface ReservationRepository {
    /** 새 예약을 항목, 할당과 함께 저장한다. 같은 멱등 키가 이미 있으면 `DuplicateReservationKeyException`을 던진다 */
    suspend fun save(reservation: Reservation): Reservation

    suspend fun findById(reservationId: Long): Reservation?

    /** 같은 채널의 같은 주문에 살아 있는 예약이 있는지. 확정된 예약과 만료 시각이 [now] 이후인 확정 전 예약이 해당한다 */
    suspend fun existsActive(
        channel: ReservationChannel,
        externalOrderId: ExternalOrderId,
        now: LocalDateTime,
    ): Boolean

    suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): Reservation?
}
