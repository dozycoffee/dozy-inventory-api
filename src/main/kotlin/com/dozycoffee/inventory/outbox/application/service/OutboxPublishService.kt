package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.outbox.application.port.`in`.PublishOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Outbox 이벤트 발행(ADR-0025, ADR-0037). 락, 조회, 발행, 상태 변경을 한 트랜잭션(한 DB 연결)에서 실행해 어드바이저리 락이 그 연결에 유지되게 한다.
 * 이벤트는 `outbox_event_id` 순서로 발행하고 브로커 확인을 받은 즉시 발행 완료로 표시한다. 발행 직후 표시 전에 죽으면 같은 이벤트가
 * 다시 발행되므로 최소 한 번 전달이다(구독자는 이벤트 ID로 중복을 거른다).
 *
 * 한 이벤트가 실패하면 같은 파티션 키의 그 뒤 이벤트는 이번 실행에서 건너뛰어(순서 보장) 다른 키는 계속 발행한다. 실패는 **같은 토픽(집계)의 다른
 * 이벤트가 이번 실행에서 발행에 성공했을 때만** 그 이벤트 고유의 문제로 보고 시도 횟수를 늘리며, 횟수가 임계치에 이르면 격리(`DEAD`)한다.
 * 그 토픽에서 하나도 성공하지 못했다면 브로커 장애나 토픽 누락 같은 공통 문제로 보고 세지 않아 이벤트를 잃지 않는다. 연속으로 [MAX_CONSECUTIVE_FAILURES]건이
 * 실패하면 공통 장애로 보고 나머지는 다음 주기로 넘겨 이벤트마다 전송 시간을 기다리지 않는다. 실패해도 이미 발행한 이벤트의 표시는 커밋한다.
 */
@Service
class OutboxPublishService(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxMessagePublisher: OutboxMessagePublisher,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : PublishOutboxEventsUseCase {
    override suspend fun publishPending(
        batchSize: Int,
        maxAttempts: Int,
    ): Int {
        require(batchSize in 1..MAX_BATCH_SIZE) { "한 번에 발행할 개수는 1 이상 $MAX_BATCH_SIZE 이하여야 한다: $batchSize" }
        require(maxAttempts >= 1) { "격리하기 전 시도 횟수는 1 이상이어야 한다: $maxAttempts" }
        return checkNotNull(transactionalOperator.executeAndAwait { publishWithLock(batchSize, maxAttempts) }) { "발행 결과가 없다" }
    }

    private suspend fun publishWithLock(
        batchSize: Int,
        maxAttempts: Int,
    ): Int {
        if (!outboxEventRepository.tryAcquirePublishLock()) return 0
        try {
            var published = 0
            var consecutiveFailures = 0
            val blockedKeys: MutableSet<String> = mutableSetOf()
            val healthyTopics: MutableSet<AggregateType> = mutableSetOf()
            val failures: MutableList<Pair<OutboxEvent, Exception>> = mutableListOf()
            for (event: OutboxEvent in outboxEventRepository.findPending(batchSize)) {
                if (event.partitionKey in blockedKeys) continue
                try {
                    outboxMessagePublisher.publish(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("이벤트 발행에 실패했다: outboxEventId={}, eventType={}", event.outboxEventId, event.eventType, e)
                    blockedKeys += event.partitionKey
                    failures += event to e
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) break
                    continue
                }
                val eventId: Long = checkNotNull(event.outboxEventId) { "저장된 이벤트는 식별자가 있어야 한다" }
                outboxEventRepository.markPublished(eventId, now())
                published++
                consecutiveFailures = 0
                healthyTopics += event.aggregateType
            }
            failures.forEach { (event: OutboxEvent, error: Exception) ->
                handleFailure(event, error, event.aggregateType in healthyTopics, maxAttempts)
            }
            return published
        } finally {
            outboxEventRepository.releasePublishLock()
        }
    }

    /** 같은 토픽의 다른 이벤트가 성공했으면 이 이벤트 고유의 실패라 세고, 아니면 공통 문제로 보고 세지 않는다 */
    private suspend fun handleFailure(
        event: OutboxEvent,
        error: Exception,
        eventSpecific: Boolean,
        maxAttempts: Int,
    ) {
        if (!eventSpecific) {
            log.warn("같은 토픽의 다른 이벤트도 발행하지 못해 공통 문제로 보고 시도 횟수를 세지 않는다: outboxEventId={}", event.outboxEventId)
            return
        }
        val dead: Boolean = event.recordFailure("${error.javaClass.simpleName}: ${error.message}", maxAttempts, now())
        if (!outboxEventRepository.saveFailure(event)) return
        if (dead) {
            log.error(
                "OUTBOX_EVENT_DEAD 발행에 계속 실패해 이벤트를 격리했다: outboxEventId={}, eventType={}, partitionKey={}, attempts={}",
                event.outboxEventId,
                event.eventType,
                event.partitionKey,
                event.attemptCount,
            )
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
}

private const val MAX_BATCH_SIZE: Int = 1000
private const val MAX_CONSECUTIVE_FAILURES: Int = 5
private val log: Logger = LoggerFactory.getLogger(OutboxPublishService::class.java)
