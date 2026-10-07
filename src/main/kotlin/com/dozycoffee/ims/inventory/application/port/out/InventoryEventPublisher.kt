package com.dozycoffee.ims.inventory.application.port.out

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장(Outbox)하는 포트 */
interface InventoryEventPublisher {
    suspend fun publish(event: InventoryEvent)
}
