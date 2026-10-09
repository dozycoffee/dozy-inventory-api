package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.outbox.application.port.`in`.CleanUpOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * 발행이 끝난 Outbox 이벤트를 보관 기간 뒤 삭제한다(ADR-0025). 삭제는 SQL 한 문장이라 트랜잭션을 따로 열지 않는다.
 * 발행 대기 이벤트는 지우지 않으므로 Kafka 장애로 오래 밀려도 이벤트를 잃지 않는다.
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
        return outboxEventRepository.deletePublishedBefore(LocalDateTime.now(clock).minus(retention), batchSize)
    }
}

private const val MAX_BATCH_SIZE: Int = 10_000
