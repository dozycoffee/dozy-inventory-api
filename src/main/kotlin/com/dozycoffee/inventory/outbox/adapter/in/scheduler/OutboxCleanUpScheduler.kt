package com.dozycoffee.inventory.outbox.adapter.`in`.scheduler

import com.dozycoffee.inventory.outbox.application.port.`in`.CleanUpOutboxEventsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 발행 완료 후 보관 기간이 지난 Outbox 이벤트를 고정 간격으로 삭제한다(ADR-0025). 묶음이 가득 찼으면 이어서 다음 묶음을 지우고
 * (한 번에 최대 [MAX_BATCHES]묶음), 실패해도 예외를 밖으로 던지지 않아 다음 주기에 다시 시도한다. 삭제는 여러 인스턴스가
 * 동시에 해도 안전해서 락을 쓰지 않는다. `inventory.outbox.cleanup.enabled=false`이면 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.outbox.cleanup", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxCleanUpScheduler(
    private val cleanUpOutboxEventsUseCase: CleanUpOutboxEventsUseCase,
    @Value("\${inventory.outbox.cleanup.retention:P7D}") private val retention: Duration,
    @Value("\${inventory.outbox.cleanup.batch-size:1000}") private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${inventory.outbox.cleanup.interval:PT1H}",
        initialDelayString = "\${inventory.outbox.cleanup.initial-delay:PT1M}",
    )
    suspend fun cleanUp() {
        try {
            repeat(MAX_BATCHES) {
                val deleted: Int = cleanUpOutboxEventsUseCase.cleanUp(retention, batchSize)
                if (deleted > 0) log.info("발행이 끝난 Outbox 이벤트 {}건을 삭제했다", deleted)
                if (deleted < batchSize) return
            }
        } catch (e: Exception) {
            log.error("Outbox 이벤트 정리에 실패했다", e)
        }
    }

    private companion object {
        const val MAX_BATCHES: Int = 10
        private val log: Logger = LoggerFactory.getLogger(OutboxCleanUpScheduler::class.java)
    }
}
