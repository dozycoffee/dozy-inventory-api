package com.dozycoffee.inventory.outbox.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult

interface GetDeadOutboxEventsUseCase {
    /** 격리된 이벤트를 최신순 페이지로 조회한다. 페이지는 0 이상, 크기는 1 이상 100 이하여야 한다([com.dozycoffee.inventory.global.error.InvalidDomainValueException]) */
    suspend fun search(
        page: Int,
        size: Int,
    ): PageResult<OutboxEventResult>

    /** 격리된 이벤트 하나를 조회한다. 없거나 격리 상태가 아니면 [com.dozycoffee.inventory.outbox.domain.exception.OutboxEventNotFoundException] */
    suspend fun getById(outboxEventId: Long): OutboxEventResult
}
