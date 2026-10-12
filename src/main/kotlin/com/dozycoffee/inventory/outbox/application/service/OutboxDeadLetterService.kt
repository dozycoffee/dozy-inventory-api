package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.outbox.application.port.`in`.GetDeadOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.RequeueOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.exception.OutboxEventNotFoundException
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.springframework.stereotype.Service

/**
 * 격리(`DEAD`)된 Outbox 이벤트를 조회하고 발행 대기로 되돌린다(ADR-0037). 되돌리기는 조건부 UPDATE 한 문장이라 트랜잭션을 따로 열지 않고,
 * 동시에 두 번 요청해도 한쪽만 성공하고 나머지는 [InvalidOutboxStateException]이다. 시도 횟수는 0으로 돌아가 발행기가 처음부터 다시 센다.
 */
@Service
class OutboxDeadLetterService(
    private val outboxEventRepository: OutboxEventRepository,
) : GetDeadOutboxEventsUseCase,
    RequeueOutboxEventUseCase {
    override suspend fun search(
        page: Int,
        size: Int,
    ): PageResult<OutboxEventResult> {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_QUERY)
        val events: List<OutboxEvent> = outboxEventRepository.findByStatus(OutboxStatus.DEAD, page.toLong() * size, size)
        val total: Long = outboxEventRepository.countByStatus(OutboxStatus.DEAD)
        return PageResult(events.map(OutboxEventResult::from), page, size, total)
    }

    override suspend fun getById(outboxEventId: Long): OutboxEventResult {
        val event: OutboxEvent = outboxEventRepository.findById(outboxEventId) ?: throw OutboxEventNotFoundException()
        if (event.status != OutboxStatus.DEAD) throw OutboxEventNotFoundException()
        return OutboxEventResult.from(event)
    }

    override suspend fun requeue(outboxEventId: Long): OutboxEventResult {
        val event: OutboxEvent = outboxEventRepository.findById(outboxEventId) ?: throw OutboxEventNotFoundException()
        event.requeue()
        if (!outboxEventRepository.saveRequeue(event)) throw InvalidOutboxStateException()
        return OutboxEventResult.from(event)
    }
}

private const val MAX_PAGE_SIZE: Int = 100
