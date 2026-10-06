package com.dozycoffee.ims.product.application.port.out

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장(Outbox)하는 포트 */
interface ProductEventPublisher {
    suspend fun publish(event: ProductEvent)
}
