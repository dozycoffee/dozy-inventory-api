package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult

interface GetInventoryLotsUseCase {
    /**
     * 재고 행이 속한 Lot의 정보를 `inventoryId` 오름차순으로 조회한다. 없는 재고 행은 결과에 나오지 않는다.
     * 예약 이벤트처럼 호출한 쪽의 트랜잭션 안에서 그 시점의 값을 읽으려고 쓴다.
     */
    suspend fun getLots(inventoryIds: Set<Long>): List<InventoryLotResult>
}
