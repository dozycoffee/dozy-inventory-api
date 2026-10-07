package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult

interface AllocateInventoryUseCase {
    /**
     * 유통기한이 이른 Lot부터 가용 수량을 예약한다. 전체 성공 또는 전체 실패이며 하나라도 모자라면 가용 수량 부족으로 실패한다.
     * 호출한 서비스의 트랜잭션 안에서 실행하며, 실패하면 호출한 쪽이 트랜잭션을 롤백해 이미 잡은 수량을 되돌린다.
     */
    suspend fun allocate(command: AllocateInventoryCommand): AllocationResult
}
