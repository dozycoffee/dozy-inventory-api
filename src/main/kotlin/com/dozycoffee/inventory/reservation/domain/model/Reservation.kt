package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
import java.time.LocalDateTime

/**
 * 주문 단위 예약. 한 창고에서 상품별 항목과 Lot(재고 행)별 할당을 가진다.
 *
 * 불변식: 처음에는 `RESERVED`이고 상품은 항목 하나씩만 가진다. 만료 시각의 규칙은 [ReservationExpiry]가,
 * 채널·주문 ID·요청 서비스의 형식은 각 값 객체가 지킨다. 채널 값은 확정되어 있지 않아 자유 문자열이다(ADR-0022).
 */
class Reservation private constructor(
    val reservationId: Long?,
    val warehouseId: Long,
    val channel: ReservationChannel,
    val externalOrderId: ExternalOrderId,
    val status: ReservationStatus,
    val expiry: ReservationExpiry,
    val confirmedAt: LocalDateTime?,
    val idempotencyKey: IdempotencyKey,
    val requesterService: RequesterService,
    val items: List<ReservationItem>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reservation) return false
        return reservationId != null && reservationId == other.reservationId
    }

    override fun hashCode(): Int = reservationId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        const val MAX_ITEMS: Int = 100

        fun create(
            warehouseId: Long,
            channel: ReservationChannel,
            externalOrderId: ExternalOrderId,
            expiry: ReservationExpiry,
            idempotencyKey: IdempotencyKey,
            requesterService: RequesterService,
            items: List<ReservationItem>,
        ): Reservation {
            if (warehouseId < 1) throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_WAREHOUSE)
            if (items.isEmpty() || items.size > MAX_ITEMS || items.map(ReservationItem::productId).toSet().size != items.size) {
                throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_ITEMS)
            }
            return Reservation(
                null,
                warehouseId,
                channel,
                externalOrderId,
                ReservationStatus.RESERVED,
                expiry,
                null,
                idempotencyKey,
                requesterService,
                items.toList(),
            )
        }

        fun reconstitute(
            reservationId: Long,
            warehouseId: Long,
            channel: ReservationChannel,
            externalOrderId: ExternalOrderId,
            status: ReservationStatus,
            expiry: ReservationExpiry,
            confirmedAt: LocalDateTime?,
            idempotencyKey: IdempotencyKey,
            requesterService: RequesterService,
            items: List<ReservationItem>,
        ): Reservation =
            Reservation(
                reservationId,
                warehouseId,
                channel,
                externalOrderId,
                status,
                expiry,
                confirmedAt,
                idempotencyKey,
                requesterService,
                items.toList(),
            )
    }
}
