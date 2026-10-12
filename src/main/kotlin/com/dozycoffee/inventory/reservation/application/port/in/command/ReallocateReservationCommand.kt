package com.dozycoffee.inventory.reservation.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 확정된 예약에서 피킹할 수 없는 Lot의 재고 행별 부족 수량. 같은 재고 행을 두 번 담을 수 없다 */
data class ReallocateReservationCommand(
    val reservationId: Long,
    val shortages: List<Shortage>,
    val idempotencyKey: String,
) {
    data class Shortage(
        val inventoryId: Long,
        val shortageQuantity: Int,
    )

    init {
        if (shortages.isEmpty() ||
            shortages.any { it.inventoryId < 1 || it.shortageQuantity < 1 } ||
            shortages.map(Shortage::inventoryId).toSet().size != shortages.size
        ) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_REALLOCATION)
        }
    }
}
