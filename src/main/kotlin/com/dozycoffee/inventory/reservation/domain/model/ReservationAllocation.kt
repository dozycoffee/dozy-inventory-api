package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 예약 항목에 할당된 재고 행(Lot)과 수량. 출고 확정된 수량은 할당 수량을 넘을 수 없다 */
class ReservationAllocation private constructor(
    val reservationAllocationId: Long?,
    val inventoryId: Long,
    val quantity: Int,
    val fulfilledQuantity: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReservationAllocation) return false
        return reservationAllocationId != null && reservationAllocationId == other.reservationAllocationId
    }

    override fun hashCode(): Int = reservationAllocationId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            inventoryId: Long,
            quantity: Int,
        ): ReservationAllocation {
            if (inventoryId < 1 || quantity < 1) throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_ITEMS)
            return ReservationAllocation(null, inventoryId, quantity, 0)
        }

        fun reconstitute(
            reservationAllocationId: Long,
            inventoryId: Long,
            quantity: Int,
            fulfilledQuantity: Int,
        ): ReservationAllocation = ReservationAllocation(reservationAllocationId, inventoryId, quantity, fulfilledQuantity)
    }
}
