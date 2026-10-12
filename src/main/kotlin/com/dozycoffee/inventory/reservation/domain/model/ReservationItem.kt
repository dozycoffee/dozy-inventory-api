package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 상품별 요청 수량과 그 수량이 할당된 재고 행들. 할당 수량의 합은 요청 수량과 같고 같은 재고 행을 두 번 할당하지 않는다 */
class ReservationItem private constructor(
    val reservationItemId: Long?,
    val productId: Long,
    val requestedQuantity: Int,
    allocations: List<ReservationAllocation>,
) {
    var allocations: List<ReservationAllocation> = allocations
        private set

    /**
     * 부족이 보고된 할당의 수량([shortages], 재고 행 ID별)을 [replacements]의 새 재고 행으로 옮긴다. 이미 할당된 재고 행이면 수량이 늘고
     * 아니면 새 할당이 생기며, 수량이 0이 된 할당은 지운다. 옮긴 합계는 부족 수량의 합과 같아야 해서 요청 수량은 그대로다.
     */
    fun reallocate(
        shortages: Map<Long, Int>,
        replacements: List<Pair<Long, Int>>,
    ) {
        if (replacements.sumOf { it.second.toLong() } != shortages.values.sumOf { it.toLong() } ||
            replacements.any { (inventoryId: Long, quantity: Int) -> quantity < 1 || inventoryId in shortages } ||
            replacements.map { it.first }.toSet().size != replacements.size
        ) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_REALLOCATION)
        }
        shortages.forEach { (inventoryId: Long, amount: Int) -> allocationOf(inventoryId).reduce(amount) }
        val added: MutableList<ReservationAllocation> = mutableListOf()
        replacements.forEach { (inventoryId: Long, quantity: Int) ->
            val existing: ReservationAllocation? = allocations.firstOrNull { it.inventoryId == inventoryId }
            if (existing != null) existing.increase(quantity) else added += ReservationAllocation.create(inventoryId, quantity)
        }
        allocations = (allocations + added).filter { it.quantity > 0 }
    }

    /** 이 항목이 재고 행의 할당을 가지고 있는지 */
    fun hasAllocation(inventoryId: Long): Boolean = allocations.any { it.inventoryId == inventoryId }

    fun allocationOf(inventoryId: Long): ReservationAllocation =
        allocations.firstOrNull { it.inventoryId == inventoryId }
            ?: throw InvalidDomainValueException(ReservationErrorCode.INVALID_REALLOCATION)

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
