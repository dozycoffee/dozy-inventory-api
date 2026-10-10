package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult

interface AdjustInventoryUseCase {
    /**
     * 실사 조정의 변동량(+/−)을 재고 행에 반영하고 `ADJUSTMENT` 이력과 증가·감소 이벤트를 남기며 반영한 행의 할당 보류를 해제한다(ADR-0026).
     * 증가는 행이 없으면 만들고, 감소는 행이 있어야 하며 가용 수량 안에서만 가능하다. 재고 행은 `inventory_id` 오름차순으로 갱신한다.
     * 호출한 서비스의 트랜잭션 안에서 실행하며, 하나라도 실패하면 호출한 쪽이 롤백한다.
     */
    suspend fun adjust(command: AdjustInventoryCommand): AdjustInventoryResult
}
