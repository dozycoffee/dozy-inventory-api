package com.dozycoffee.inventory.reservation.adapter.`in`.scheduler

import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.reservation.application.port.`in`.ExpireReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FindExpiredReservationsUseCase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 만료 시각이 지난 확정 전 예약을 고정 간격으로 만료 처리한다(ADR-0023). 만료 대상을 가져와 예약 하나씩 [ExpireReservationUseCase]에
 * 넘기고, 한 건이 실패해도 로그만 남기고 나머지를 계속 처리한다. 여러 인스턴스가 동시에 돌아도 만료 처리가 조건부 UPDATE라
 * 같은 예약은 한 번만 처리된다. 요청 밖 작업이라 [SystemActor]로 실행한다.
 * `inventory.reservation.expiry-scan.enabled=false`이면 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.reservation.expiry-scan", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReservationExpiryScheduler(
    private val findExpiredReservationsUseCase: FindExpiredReservationsUseCase,
    private val expireReservationUseCase: ExpireReservationUseCase,
    @Value("\${inventory.reservation.expiry-scan.batch-size:100}") private val batchSize: Int,
) {
    @Scheduled(fixedDelayString = "\${inventory.reservation.expiry-scan.interval:PT1M}")
    suspend fun scan() {
        try {
            ActorContext.with(SystemActor) { expireDueReservations() }
        } catch (e: Exception) {
            log.error("예약 만료 스캔에 실패했다", e)
        }
    }

    /** 한 번에 최대 [MAX_BATCHES]묶음까지 처리한다. 묶음이 가득 찼고 그중 하나라도 처리했을 때만 다음 묶음을 이어서 가져온다 */
    private suspend fun expireDueReservations() {
        repeat(MAX_BATCHES) {
            val ids: List<Long> = findExpiredReservationsUseCase.findExpiredIds(batchSize)
            if (ids.isEmpty()) return
            val expired: Int = ids.count { expireOne(it) }
            if (expired > 0) log.info("예약 {}건을 만료 처리했다(대상 {}건)", expired, ids.size)
            if (ids.size < batchSize || expired == 0) return
        }
    }

    private suspend fun expireOne(reservationId: Long): Boolean =
        try {
            expireReservationUseCase.expire(reservationId)
        } catch (e: Exception) {
            log.error("예약 만료 처리에 실패했다: reservationId={}", reservationId, e)
            false
        }

    private companion object {
        const val MAX_BATCHES: Int = 10
        private val log: Logger = LoggerFactory.getLogger(ReservationExpiryScheduler::class.java)
    }
}
