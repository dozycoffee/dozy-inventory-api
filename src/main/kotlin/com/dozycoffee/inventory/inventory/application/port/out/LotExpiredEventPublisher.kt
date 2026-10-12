package com.dozycoffee.inventory.inventory.application.port.out

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장(Outbox)하는 포트 */
interface LotExpiredEventPublisher {
    suspend fun publish(event: LotExpiredEvent)
}
