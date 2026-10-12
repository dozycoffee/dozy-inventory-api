package com.dozycoffee.inventory.adjustment.adapter.`in`.scheduler

import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 하루에 한 번 WMS 수량과 대사한다(ADR-0033). 입고·출고가 적은 새벽에 돌린다. 실패해도 예외를 밖으로 던지지 않아 다음 실행에서 다시 한다.
 * 보정안 생성이 멱등이라 여러 인스턴스가 동시에 돌아도 안전하다. 요청 밖 작업이라 [SystemActor]로 실행한다.
 * `inventory.reconciliation.enabled=false`이면 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.reconciliation", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler(
    private val reconcileStockUseCase: ReconcileStockUseCase,
) {
    @Scheduled(
        cron = "\${inventory.reconciliation.cron:0 30 1 * * *}",
        zone = "\${inventory.reconciliation.zone:Asia/Seoul}",
    )
    suspend fun reconcile() {
        try {
            ActorContext.with(SystemActor) { reconcileStockUseCase.reconcile() }
        } catch (e: Exception) {
            log.error("대사 배치에 실패했다", e)
        }
    }

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(ReconciliationScheduler::class.java)
    }
}
