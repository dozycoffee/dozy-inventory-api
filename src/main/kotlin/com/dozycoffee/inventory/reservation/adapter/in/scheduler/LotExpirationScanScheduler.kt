package com.dozycoffee.inventory.reservation.adapter.`in`.scheduler

import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.reservation.application.port.`in`.ScanLotExpirationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 유통기한이 지난 Lot을 하루에 한 번 처리한다(ADR-0027). 한 번에 최대 [MAX_BATCHES]묶음까지 이어서 처리하고, 묶음이 가득 차지 않았거나
 * 성공한 Lot이 없으면 멈춘다(계속 실패하는 Lot 때문에 같은 묶음을 반복하지 않는다). 실패해도 예외를 밖으로 던지지 않아 다음 실행에서
 * 이어서 처리한다. 스캔이 멱등이고 조건부라 여러 인스턴스가 동시에 돌아도 안전하다. 요청 밖 작업이라 [SystemActor]로 실행한다.
 * `inventory.lot-expiration-scan.enabled=false`이면 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.lot-expiration-scan", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LotExpirationScanScheduler(
    private val scanLotExpirationUseCase: ScanLotExpirationUseCase,
    @Value("\${inventory.lot-expiration-scan.batch-size:100}") private val batchSize: Int,
) {
    @Scheduled(
        cron = "\${inventory.lot-expiration-scan.cron:0 10 0 * * *}",
        zone = "\${inventory.lot-expiration-scan.zone:Asia/Seoul}",
    )
    suspend fun scan() {
        try {
            ActorContext.with(SystemActor) { scanBatches() }
        } catch (e: Exception) {
            log.error("유통기한 스캔에 실패했다", e)
        }
    }

    private suspend fun scanBatches() {
        repeat(MAX_BATCHES) {
            val result: LotExpirationScanResult = scanLotExpirationUseCase.scan(batchSize)
            if (result.attempted > 0) {
                log.info("유통기한 스캔: Lot {}건 중 {}건 처리, 예약 {}건 강제 해제", result.attempted, result.processed, result.forceReleased)
            }
            if (result.attempted < batchSize || result.processed == 0) return
        }
    }

    private companion object {
        const val MAX_BATCHES: Int = 10
        private val log: Logger = LoggerFactory.getLogger(LotExpirationScanScheduler::class.java)
    }
}
