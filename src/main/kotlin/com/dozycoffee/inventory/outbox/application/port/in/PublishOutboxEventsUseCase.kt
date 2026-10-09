package com.dozycoffee.inventory.outbox.application.port.`in`

interface PublishOutboxEventsUseCase {
    /**
     * 발행 대기 이벤트를 `outbox_event_id` 순서대로 최대 [batchSize]개 발행하고 발행한 개수를 반환한다. 여러 인스턴스 중 발행기 락을 잡은
     * 하나만 발행하고 나머지는 0을 반환한다. 발행에 실패하면 그 이벤트의 시도 횟수를 늘리고 그 뒤 이벤트는 발행하지 않는다(순서 보장).
     */
    suspend fun publishPending(batchSize: Int): Int
}
