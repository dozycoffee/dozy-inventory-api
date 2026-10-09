package com.dozycoffee.inventory.outbox.adapter.`in`.scheduler

import com.dozycoffee.inventory.outbox.application.port.`in`.PublishOutboxEventsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Outbox 대기 이벤트를 고정 간격으로 발행한다(ADR-0025). 묶음이 가득 찼으면 이어서 다음 묶음을 발행하고(한 번에 최대 [MAX_BATCHES]묶음),
 * 실패해도 예외를 밖으로 던지지 않아 다음 주기에 다시 시도한다. 여러 인스턴스가 돌아도 발행기 락을 잡은 하나만 발행한다.
 * `inventory.outbox.publisher.enabled=false`이면 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.outbox.publisher", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPublishScheduler(
    private val publishOutboxEventsUseCase: PublishOutboxEventsUseCase,
    @Value("\${inventory.outbox.publisher.batch-size:100}") private val batchSize: Int,
) {
    @Scheduled(fixedDelayString = "\${inventory.outbox.publisher.interval:PT1S}")
    suspend fun publish() {
        try {
            repeat(MAX_BATCHES) {
                val published: Int = publishOutboxEventsUseCase.publishPending(batchSize)
                if (published > 0) log.info("Outbox 이벤트 {}건을 발행했다", published)
                if (published < batchSize) return
            }
        } catch (e: Exception) {
            log.error("Outbox 발행에 실패했다", e)
        }
    }

    private companion object {
        const val MAX_BATCHES: Int = 10
        private val log: Logger = LoggerFactory.getLogger(OutboxPublishScheduler::class.java)
    }
}
