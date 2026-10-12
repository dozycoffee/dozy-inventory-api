package com.dozycoffee.inventory.outbox.application.port.`in`

import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult

interface RequeueOutboxEventUseCase {
    /**
     * 격리된 이벤트를 발행 대기로 되돌려 발행기가 다시 가져가게 한다. 없으면 [com.dozycoffee.inventory.outbox.domain.exception.OutboxEventNotFoundException],
     * 격리 상태가 아니면(이미 되돌려졌거나 발행됨) [com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException]이다.
     */
    suspend fun requeue(outboxEventId: Long): OutboxEventResult
}
