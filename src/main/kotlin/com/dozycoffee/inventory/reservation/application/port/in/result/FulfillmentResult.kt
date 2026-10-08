package com.dozycoffee.inventory.reservation.application.port.`in`.result

import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem

/**
 * 출고 확정 결과. 재고 행별로 할당·출고·결품 수량을 주고, [Allocation.quantityAfter]는 출고한 행의 처리 후 총 수량이다.
 * 출고하지 않은 행은 총 수량이 바뀌지 않아 null이다. 재요청에도 처음 응답과 같은 값이다.
 */
data class FulfillmentResult(
    val reservationId: Long,
    val status: ReservationStatus,
    val allocations: List<Allocation>,
) {
    data class Allocation(
        val productId: Long,
        val inventoryId: Long,
        val allocatedQuantity: Int,
        val shippedQuantity: Int,
        val shortageQuantity: Int,
        val quantityAfter: Int?,
    )

    companion object {
        /** [quantitiesAfter]는 재고 행 ID별 처리 후 총 수량이다. 출고한 행만 들어 있다 */
        fun from(
            reservation: Reservation,
            quantitiesAfter: Map<Long, Int>,
        ): FulfillmentResult =
            FulfillmentResult(
                reservationId = checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" },
                status = reservation.status,
                allocations =
                    reservation.items.flatMap { item: ReservationItem ->
                        item.allocations.map { allocation: ReservationAllocation ->
                            Allocation(
                                productId = item.productId,
                                inventoryId = allocation.inventoryId,
                                allocatedQuantity = allocation.quantity,
                                shippedQuantity = allocation.fulfilledQuantity,
                                shortageQuantity = allocation.shortageQuantity,
                                quantityAfter = quantitiesAfter[allocation.inventoryId],
                            )
                        }
                    },
            )
    }
}
