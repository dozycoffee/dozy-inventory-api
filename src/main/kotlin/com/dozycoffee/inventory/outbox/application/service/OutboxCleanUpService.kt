package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.outbox.application.port.`in`.CleanUpOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import kotlinx.coroutines.delay
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 발행이 끝난 Outbox 이벤트를 보관 기간 뒤 삭제한다(ADR-0025). 삭제는 SQL 한 문장이라 트랜잭션을 따로 열지 않는다.
 * 발행 대기 이벤트는 지우지 않으므로 Kafka 장애로 오래 밀려도 이벤트를 잃지 않는다.
 * 여러 인스턴스가 같은 행 범위를 동시에 지우면 MySQL이 한쪽을 교착으로 되돌릴 수 있다. 이 삭제는 다시 실행해도 같은 결과라
 * 일시적인 DB 오류(교착, 락 대기 시간 초과)는 잠깐 쉬고 [MAX_ATTEMPTS]번까지 다시 시도한다.
 */
@Service
class OutboxCleanUpService(
    private val outboxEventRepository: OutboxEventRepository,
    private val clock: Clock,
) : CleanUpOutboxEventsUseCase {
    override suspend fun cleanUp(
        retention: Duration,
        batchSize: Int,
    ): Int {
        require(!retention.isNegative) { "보관 기간은 0 이상이어야 한다: $retention" }
        require(batchSize in 1..MAX_BATCH_SIZE) { "한 번에 삭제할 개수는 1 이상 $MAX_BATCH_SIZE 이하여야 한다: $batchSize" }
        val cutoff: LocalDateTime = LocalDateTime.now(clock).minus(retention)
        repeat(MAX_ATTEMPTS - 1) {
            try {
                return outboxEventRepository.deletePublishedBefore(cutoff, batchSize)
            } catch (e: TransientDataAccessException) {
                delay(Random.nextLong(MIN_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS))
            }
        }
        return outboxEventRepository.deletePublishedBefore(cutoff, batchSize)
    }
}

private const val MAX_BATCH_SIZE: Int = 10_000
private const val MAX_ATTEMPTS: Int = 5
private const val MIN_BACKOFF_MILLIS: Long = 10L
private const val MAX_BACKOFF_MILLIS: Long = 50L
