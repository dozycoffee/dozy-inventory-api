package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 상품별 요청 수량과 그 수량이 할당된 재고 행들. 할당 수량의 합은 요청 수량과 같고 같은 재고 행을 두 번 할당하지 않는다 */
class ReservationItem private constructor(
    val reservationItemId: Long?,
    val productId: Long,
    val requestedQuantity: Int,
    val allocations: List<ReservationAllocation>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReservationItem) return false
        return reservationItemId != null && reservationItemId == other.reservationItemId
    }

    override fun hashCode(): Int = reservationItemId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            productId: Long,
            requestedQuantity: Int,
            allocations: List<ReservationAllocation>,
        ): ReservationItem {
            if (productId < 1 ||
                requestedQuantity < 1 ||
                allocations.isEmpty() ||
                allocations.sumOf { it.quantity.toLong() } != requestedQuantity.toLong() ||
                allocations.map(ReservationAllocation::inventoryId).toSet().size != allocations.size
            ) {
                throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_ITEMS)
            }
            return ReservationItem(null, productId, requestedQuantity, allocations.toList())
        }

        fun reconstitute(
            reservationItemId: Long,
            productId: Long,
            requestedQuantity: Int,
            allocations: List<ReservationAllocation>,
        ): ReservationItem = ReservationItem(reservationItemId, productId, requestedQuantity, allocations.toList())
    }
}
