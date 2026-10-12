package com.dozycoffee.inventory.reservation.application.port.`in`.result

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import java.time.LocalDate

/**
 * 재할당 뒤의 예약 전체 할당. WMS는 이 목록으로 피킹 지시를 바꾼다. [heldInventoryIds]는 부족이 보고되어 신규 할당에서 제외된 재고 행이다.
 * 재요청에는 처음 요청이 만든 재할당을 반영한 예약의 현재 할당을 주므로 그 사이 바뀌었으면 바뀐 값이다.
 */
data class ReallocationResult(
    val reservationId: Long,
    val status: ReservationStatus,
    val heldInventoryIds: List<Long>,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val allocations: List<Allocation>,
    )

    data class Allocation(
        val inventoryId: Long,
        val lotId: Long,
        val lotNumber: String,
        val expirationDate: LocalDate?,
        val quantity: Int,
    )

    companion object {
        /** [lots]는 예약이 할당받은 모든 재고 행의 Lot 정보다 */
        fun from(
            reservation: Reservation,
            heldInventoryIds: Set<Long>,
            lots: Map<Long, InventoryLotResult>,
        ): ReallocationResult =
            ReallocationResult(
                reservationId = checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" },
                status = reservation.status,
                heldInventoryIds = heldInventoryIds.sorted(),
                items =
                    reservation.items.map { item: ReservationItem ->
                        Item(
                            item.productId,
                            item.allocations.map {
                                val lot: InventoryLotResult = checkNotNull(lots[it.inventoryId]) { "재고 행의 Lot을 찾을 수 없다: ${it.inventoryId}" }
                                Allocation(it.inventoryId, lot.lotId, lot.lotNumber, lot.expirationDate, it.quantity)
                            },
                        )
                    },
            )
    }
}
