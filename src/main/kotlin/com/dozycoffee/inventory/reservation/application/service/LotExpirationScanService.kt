package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.FindExpiredLotInventoryIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.FindLotsToScanUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ProcessLotExpirationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FindReservationsByInventoryUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ForceReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ScanLotExpirationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 유통기한 스캔의 오케스트레이션(ADR-0027). 도메인 의존 방향이 `reservation` → `inventory`라서 예약을 아는 이 도메인이 inventory의
 * Lot 처리를 부른다. Lot마다 순서가 중요하다: 확정 전 예약을 먼저 풀어야 그 수량이 가용으로 돌아와 같은 스캔에서 폐기 예정으로 옮겨지고,
 * 확정 예약은 그 뒤에 조회해야 해제와 겹쳐 확정된 예약까지 건너뛸 목록에 들어간다. 각 단계는 자기 트랜잭션이고 단계마다 멱등이라
 * 중간에 실패하면 다음 스캔이 이어서 처리한다.
 */
@Service
class LotExpirationScanService(
    private val findLotsToScanUseCase: FindLotsToScanUseCase,
    private val findExpiredLotInventoryIdsUseCase: FindExpiredLotInventoryIdsUseCase,
    private val processLotExpirationUseCase: ProcessLotExpirationUseCase,
    private val findReservationsByInventoryUseCase: FindReservationsByInventoryUseCase,
    private val forceReleaseReservationUseCase: ForceReleaseReservationUseCase,
) : ScanLotExpirationUseCase {
    override suspend fun scan(limit: Int): LotExpirationScanResult {
        val lotIds: List<Long> = findLotsToScanUseCase.findLotIds(limit)
        var processed = 0
        var forceReleased = 0
        for (lotId: Long in lotIds) {
            try {
                forceReleased += processLot(lotId)
                processed++
            } catch (e: Exception) {
                log.error("유통기한 스캔에서 Lot 처리에 실패했다: lotId={}", lotId, e)
            }
        }
        return LotExpirationScanResult(lotIds.size, processed, forceReleased)
    }

    /** Lot 하나를 처리하고 강제 해제한 예약 수를 반환한다 */
    private suspend fun processLot(lotId: Long): Int {
        val inventoryIds: Set<Long> = findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(lotId).toSet()
        val released: Int = forceReleaseUnconfirmed(inventoryIds)
        val confirmedIds: List<Long> = findReservationsByInventoryUseCase.findConfirmedIds(inventoryIds)
        processLotExpirationUseCase.process(lotId, confirmedIds)
        return released
    }

    /** 확정 전 예약을 하나씩 강제 해제한다. 한 건이 실패해도 나머지를 계속하고, 실패한 예약은 다음 스캔에서 다시 시도한다 */
    private suspend fun forceReleaseUnconfirmed(inventoryIds: Set<Long>): Int {
        if (inventoryIds.isEmpty()) return 0
        return findReservationsByInventoryUseCase.findUnconfirmedIds(inventoryIds).count { reservationId: Long ->
            try {
                forceReleaseReservationUseCase.forceRelease(reservationId, REASON)
            } catch (e: Exception) {
                log.error("유통기한 경과 예약 강제 해제에 실패했다: reservationId={}", reservationId, e)
                false
            }
        }
    }
}

private const val REASON: String = "LOT_EXPIRED"
private val log: Logger = LoggerFactory.getLogger(LotExpirationScanService::class.java)
