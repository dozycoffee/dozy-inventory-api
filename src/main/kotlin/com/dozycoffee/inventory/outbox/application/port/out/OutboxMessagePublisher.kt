package com.dozycoffee.inventory.outbox.application.port.out

import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent

/** 이벤트를 메시지 브로커로 내보내는 포트. 브로커가 받았다는 확인까지 기다리고, 실패하면 예외를 던진다 */
interface OutboxMessagePublisher {
    suspend fun publish(event: OutboxEvent)
}
