package com.dozycoffee.inventory.reservation.application.port.`in`

interface FindReservationsByInventoryUseCase {
    /** 주어진 재고 행에 수량을 할당받은 확정 전(`RESERVED`) 예약의 ID를 ID 순서로 반환한다 */
    suspend fun findUnconfirmedIds(inventoryIds: Set<Long>): List<Long>

    /** 주어진 재고 행에 수량을 할당받은 확정된(`CONFIRMED`) 예약의 ID를 ID 순서로 반환한다 */
    suspend fun findConfirmedIds(inventoryIds: Set<Long>): List<Long>
}
