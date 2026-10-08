package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationExpiredException
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
    status: ReservationStatus,
    expiry: ReservationExpiry,
    confirmedAt: LocalDateTime?,
    val idempotencyKey: IdempotencyKey,
    val requesterService: RequesterService,
    val items: List<ReservationItem>,
) {
    var status: ReservationStatus = status
        private set

    var expiry: ReservationExpiry = expiry
        private set

    var confirmedAt: LocalDateTime? = confirmedAt
        private set

    /**
     * 확정 전 예약을 확정한다. 확정된 예약은 만료되지 않아 만료 시각이 사라진다. 이미 확정되었으면 바꾸지 않고 false를 반환한다.
     * 만료 시각이 지난 예약은 [ReservationExpiredException], 해제·만료·출고 완료된 예약은 [InvalidReservationStateException]이다.
     */
    fun confirm(now: LocalDateTime): Boolean =
        when (status) {
            ReservationStatus.CONFIRMED -> {
                false
            }

            ReservationStatus.RESERVED -> {
                if (expiry.isExpiredAt(now)) throw ReservationExpiredException()
                status = ReservationStatus.CONFIRMED
                expiry = expiry.cleared()
                confirmedAt = now
                true
            }

            else -> {
                throw InvalidReservationStateException()
            }
        }

    /**
     * 예약을 해제한다. 확정 전이든 확정되었든 해제할 수 있다. 이미 해제되었거나 만료되었으면 바꾸지 않고 false를 반환하고,
     * 출고 완료된 예약은 [InvalidReservationStateException]이다. 수량을 되돌리는 일은 호출한 서비스가 한다.
     */
    fun release(): Boolean =
        when (status) {
            ReservationStatus.RELEASED, ReservationStatus.EXPIRED -> {
                false
            }

            ReservationStatus.RESERVED, ReservationStatus.CONFIRMED -> {
                status = ReservationStatus.RELEASED
                expiry = expiry.cleared()
                true
            }

            ReservationStatus.FULFILLED -> {
                throw InvalidReservationStateException()
            }
        }

    /**
     * 확정 전 예약의 만료 시각을 [newExpiresAt]으로 늘린다. 같은 시각이면 바꾸지 않고 false를 반환한다.
     * 만료 시각이 지난 예약은 [ReservationExpiredException], 확정 전이 아닌 예약은 [InvalidReservationStateException]이고,
     * 새 만료 시각이 현재 이전이거나 현재 만료 시각보다 이르거나 최대 만료 시각을 넘으면 입력 오류다.
     */
    fun extend(
        newExpiresAt: LocalDateTime,
        now: LocalDateTime,
    ): Boolean {
        if (status != ReservationStatus.RESERVED) throw InvalidReservationStateException()
        if (expiry.isExpiredAt(now)) throw ReservationExpiredException()
        if (newExpiresAt == expiry.expiresAt) return false
        expiry = expiry.extendedTo(newExpiresAt, now)
        return true
    }

    /**
     * 확정된 예약의 출고를 확정한다(ADR-0024). [shipments]는 재고 행 ID별 실제 출고 수량이며 예약의 모든 할당 재고 행을 한 번씩 담아야 하고
     * 각 수량은 0 이상 할당 수량 이하여야 한다(어기면 입력 오류). 결품(할당 − 출고)은 [ReservationAllocation.shortageQuantity]로 남는다.
     * 이미 출고 확정된 예약은 같은 수량이면 바꾸지 않고 false를 반환하고 다르면 [InvalidReservationStateException]이다.
     * 확정 전·해제·만료된 예약은 [InvalidReservationStateException]이다.
     */
    fun fulfill(shipments: Map<Long, Int>): Boolean {
        val allocations: List<ReservationAllocation> = items.flatMap(ReservationItem::allocations)
        if (status == ReservationStatus.FULFILLED) {
            if (!matches(allocations, shipments)) throw InvalidReservationStateException()
            return false
        }
        if (status != ReservationStatus.CONFIRMED) throw InvalidReservationStateException()
        if (shipments.keys != allocations.map(ReservationAllocation::inventoryId).toSet()) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_FULFILLMENT)
        }
        if (shipments.any { (inventoryId: Long, shipped: Int) ->
                shipped < 0 ||
                    shipped > allocations.first { it.inventoryId == inventoryId }.quantity
            }
        ) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_FULFILLMENT)
        }
        allocations.forEach { it.fulfill(shipments.getValue(it.inventoryId)) }
        status = ReservationStatus.FULFILLED
        return true
    }

    private fun matches(
        allocations: List<ReservationAllocation>,
        shipments: Map<Long, Int>,
    ): Boolean =
        shipments.keys == allocations.map(ReservationAllocation::inventoryId).toSet() &&
            allocations.all { shipments[it.inventoryId] == it.fulfilledQuantity }

    /** 만료 시각이 지난 확정 전 예약을 만료 처리한다. 대상이 아니면 바꾸지 않고 false를 반환한다 */
    fun expire(now: LocalDateTime): Boolean {
        if (status != ReservationStatus.RESERVED || !expiry.isExpiredAt(now)) return false
        status = ReservationStatus.EXPIRED
        return true
    }

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
