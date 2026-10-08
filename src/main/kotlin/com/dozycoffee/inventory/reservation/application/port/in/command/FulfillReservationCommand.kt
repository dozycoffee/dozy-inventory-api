package com.dozycoffee.inventory.reservation.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/**
 * 예약의 출고 확정. [allocations]는 예약의 모든 할당 재고 행의 실제 출고 수량이며 같은 재고 행을 두 번 담을 수 없다.
 * [requesterService]는 요청 주체(system client의 principalId)다.
 */
data class FulfillReservationCommand(
    val reservationId: Long,
    val allocations: List<Allocation>,
    val idempotencyKey: String,
    val requesterService: String,
) {
    data class Allocation(
        val inventoryId: Long,
        val shippedQuantity: Int,
    )

    init {
        if (allocations.isEmpty() ||
            allocations.any { it.inventoryId < 1 || it.shippedQuantity < 0 } ||
            allocations.map(Allocation::inventoryId).toSet().size != allocations.size
        ) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_FULFILLMENT)
        }
    }
}
