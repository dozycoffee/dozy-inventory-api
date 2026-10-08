package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand

interface ReleaseInventoryUseCase {
    /**
     * 예약으로 잡은 수량을 재고 행별로 되돌려 가용 수량으로 돌린다. 예약 수량보다 많이 되돌리려 하면 실패한다.
     * 호출한 서비스의 트랜잭션 안에서 실행하며, 실패하면 호출한 쪽이 롤백해 이미 되돌린 수량도 원래대로 둔다.
     */
    suspend fun release(command: ReleaseInventoryCommand)
}
