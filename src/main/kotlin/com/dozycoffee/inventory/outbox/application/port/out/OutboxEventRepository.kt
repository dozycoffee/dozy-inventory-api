package com.dozycoffee.inventory.outbox.application.port.out

import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장하고 조회한다 */
interface OutboxEventRepository {
    suspend fun save(event: OutboxEvent): OutboxEvent

    suspend fun findById(outboxEventId: Long): OutboxEvent?
}
