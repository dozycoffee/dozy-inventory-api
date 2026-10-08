package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult

interface ShipInventoryUseCase {
    /**
     * 출고한 수량만큼 총 수량과 예약 수량을 함께 줄이고 `OUTBOUND` 이력을 남기며, 결품 수량은 예약 수량만 되돌려 가용으로 돌린다.
     * 재고 행은 `inventory_id` 오름차순으로 갱신한다. 호출한 서비스의 트랜잭션 안에서 실행하며, 실패하면 호출한 쪽이 롤백한다.
     */
    suspend fun ship(command: ShipInventoryCommand): ShipResult
}
