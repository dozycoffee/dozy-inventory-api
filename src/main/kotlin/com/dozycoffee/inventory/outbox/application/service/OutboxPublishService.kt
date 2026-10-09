package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.outbox.application.port.`in`.PublishOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Outbox 이벤트 발행(ADR-0025). 락, 조회, 발행, 상태 변경을 한 트랜잭션(한 DB 연결)에서 실행해 어드바이저리 락이 그 연결에 유지되게 한다.
 * 이벤트는 `outbox_event_id` 순서로 발행하고 브로커 확인을 받은 즉시 발행 완료로 표시한다. 발행 직후 표시 전에 죽으면 같은 이벤트가
 * 다시 발행되므로 최소 한 번 전달이다(구독자는 이벤트 ID로 중복을 거른다). 한 이벤트가 실패하면 시도 횟수만 늘리고 그 묶음의 발행을 멈춰
 * 같은 파티션 키의 순서가 뒤집히지 않게 한다. 실패해도 이미 발행한 이벤트의 표시는 커밋한다.
 */
@Service
class OutboxPublishService(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxMessagePublisher: OutboxMessagePublisher,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : PublishOutboxEventsUseCase {
    override suspend fun publishPending(batchSize: Int): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "한 번에 발행할 개수는 1 이상 $MAX_BATCH_SIZE 이하여야 한다: $batchSize" }
        return checkNotNull(transactionalOperator.executeAndAwait { publishWithLock(batchSize) }) { "발행 결과가 없다" }
    }

    private suspend fun publishWithLock(batchSize: Int): Int {
        if (!outboxEventRepository.tryAcquirePublishLock()) return 0
        try {
            var published = 0
            for (event: OutboxEvent in outboxEventRepository.findPending(batchSize)) {
                val eventId: Long = checkNotNull(event.outboxEventId) { "저장된 이벤트는 식별자가 있어야 한다" }
                try {
                    outboxMessagePublisher.publish(event)
                } catch (e: Exception) {
                    log.error("이벤트 발행에 실패했다: outboxEventId={}, eventType={}", eventId, event.eventType, e)
                    outboxEventRepository.increaseAttemptCount(eventId)
                    break
                }
                outboxEventRepository.markPublished(eventId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS))
                published++
            }
            return published
        } finally {
            outboxEventRepository.releasePublishLock()
        }
    }
}

private const val MAX_BATCH_SIZE: Int = 1000
private val log: Logger = LoggerFactory.getLogger(OutboxPublishService::class.java)
