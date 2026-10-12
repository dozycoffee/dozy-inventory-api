package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.FindExpiredLotInventoryIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.FindLotsToScanUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ProcessLotExpirationUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotExpirationResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotExpiredEvent
import com.dozycoffee.inventory.inventory.application.port.out.LotExpiredEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDate

/**
 * 유통기한 스캔의 inventory 쪽 처리(ADR-0027). Lot 하나를 한 트랜잭션으로 처리한다: 상태를 조건부로 갱신하고, 경과했으면
 * 정상 품질 행의 가용 수량을 폐기 예정 행으로 옮기며 `QUALITY_TRANSFER` 이력을 전환 전 행에 −N, 전환 후 행에 +N으로 남긴다.
 * 재고 증감 이벤트는 발행하지 않고, 이번에 경과로 바뀌었거나 수량을 전환했으면 `Lot 경과` 이벤트를 같은 트랜잭션에 저장한다. 같은 날 같은 Lot의 이력 멱등 키는 같아서 두 번째 처리는 중복 키로 되돌아가고 변화 없는 결과가 된다.
 * 전환할 행이 예약으로 가용 수량이 줄었으면 그 행은 건너뛰고 다음 스캔이 이어서 처리한다.
 */
@Service
class LotExpirationService(
    private val lotRepository: LotRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val lotExpiredEventPublisher: LotExpiredEventPublisher,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
    @Value("\${inventory.expiring-soon-days}") private val expiringSoonDays: Int,
) : FindLotsToScanUseCase,
    FindExpiredLotInventoryIdsUseCase,
    ProcessLotExpirationUseCase {
    override suspend fun findLotIds(limit: Int): List<Long> {
        require(limit in 1..MAX_SCAN_SIZE) { "한 번에 가져올 Lot 개수는 1 이상 $MAX_SCAN_SIZE 이하여야 한다: $limit" }
        val today: LocalDate = LocalDate.now(clock)
        return lotRepository.findScanCandidateIds(today, today.plusDays(expiringSoonDays.toLong()), limit)
    }

    override suspend fun findReservedInventoryIds(lotId: Long): List<Long> {
        val lot: Lot = lotRepository.findById(lotId) ?: throw LotNotFoundException()
        val expirationDate: LocalDate = lot.expirationDate ?: return emptyList()
        if (expirationDate.isAfter(LocalDate.now(clock))) return emptyList()
        return inventoryRepository
            .findAllByLot(lotId, QualityStatus.NORMAL)
            .filter { it.reservedQuantity > 0 }
            .mapNotNull(Inventory::inventoryId)
    }

    override suspend fun process(
        lotId: Long,
        confirmedReservationIds: List<Long>,
    ): LotExpirationResult {
        val today: LocalDate = LocalDate.now(clock)
        return try {
            checkNotNull(transactionalOperator.executeAndAwait { processInTransaction(lotId, today, confirmedReservationIds) }) {
                "Lot 처리 결과가 없다"
            }
        } catch (e: DuplicateIdempotencyKeyException) {
            unchanged(lotRepository.findById(lotId) ?: throw LotNotFoundException())
        }
    }

    private suspend fun processInTransaction(
        lotId: Long,
        today: LocalDate,
        confirmedReservationIds: List<Long>,
    ): LotExpirationResult {
        val lot: Lot = lotRepository.findById(lotId) ?: throw LotNotFoundException()
        val before: LotStatus = lot.lotStatus
        val statusChanged: Boolean = lot.refreshStatus(today, expiringSoonDays) && lotRepository.updateStatus(lot, before)
        val expired: Boolean = lot.lotStatus == LotStatus.EXPIRED && (statusChanged || before == LotStatus.EXPIRED)
        val transfers: List<LotExpirationResult.Transfer> = if (expired) transferAvailable(lot, today) else emptyList()
        val becameExpired: Boolean = statusChanged && lot.lotStatus == LotStatus.EXPIRED
        if (becameExpired || transfers.isNotEmpty()) publishEvent(lot, transfers, confirmedReservationIds)
        return LotExpirationResult(
            lotId = lotId,
            productId = lot.productId,
            lotNumber = lot.lotNumber,
            expirationDate = lot.expirationDate,
            statusChanged = statusChanged,
            becameExpired = becameExpired,
            expired = expired,
            transfers = transfers,
        )
    }

    private suspend fun publishEvent(
        lot: Lot,
        transfers: List<LotExpirationResult.Transfer>,
        confirmedReservationIds: List<Long>,
    ) {
        lotExpiredEventPublisher.publish(
            LotExpiredEvent(
                lotId = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" },
                productId = lot.productId,
                lotNumber = lot.lotNumber,
                expirationDate = lot.expirationDate,
                transfers =
                    transfers.map {
                        LotExpiredEvent.Transfer(
                            it.warehouseId,
                            it.sourceInventoryId,
                            it.targetInventoryId,
                            it.quantity,
                        )
                    },
                confirmedReservationIds = confirmedReservationIds.distinct().sorted(),
            ),
        )
    }

    /** 정상 품질 행의 가용 수량만 `inventory_id` 오름차순으로 옮긴다. 그 사이 가용 수량이 줄어 옮길 수 없는 행은 건너뛴다 */
    private suspend fun transferAvailable(
        lot: Lot,
        today: LocalDate,
    ): List<LotExpirationResult.Transfer> {
        val lotId: Long = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" }
        val key: IdempotencyKey = IdempotencyKey.of("lot-expiration-$lotId-$today")
        return inventoryRepository
            .findAllByLot(lotId, QualityStatus.NORMAL)
            .filter { it.availableQuantity > 0 }
            .mapNotNull { row: Inventory -> transferOne(row, lotId, key) }
    }

    private suspend fun transferOne(
        row: Inventory,
        lotId: Long,
        key: IdempotencyKey,
    ): LotExpirationResult.Transfer? {
        val amount: Int = row.availableQuantity
        val sourceId: Long = checkNotNull(row.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }
        val source: Inventory =
            try {
                inventoryRepository.decrease(sourceId, amount)
            } catch (e: InsufficientAvailableQuantityException) {
                return null
            }
        val target: Inventory =
            inventoryRepository.increase(
                InventoryKey(row.warehouseId, lotId, QualityStatus.DISPOSAL_SCHEDULED),
                row.productId,
                amount,
            )
        val targetId: Long = checkNotNull(target.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }
        saveHistory(sourceId, -amount, source.quantity, lotId, key)
        saveHistory(targetId, amount, target.quantity, lotId, key)
        return LotExpirationResult.Transfer(row.warehouseId, sourceId, targetId, amount, source.reservedQuantity)
    }

    private suspend fun saveHistory(
        inventoryId: Long,
        quantityChange: Int,
        quantityAfter: Int,
        lotId: Long,
        key: IdempotencyKey,
    ) {
        inventoryHistoryRepository.save(
            InventoryHistory.create(
                inventoryId = inventoryId,
                historyType = HistoryType.QUALITY_TRANSFER,
                quantityChange = quantityChange,
                quantityAfter = quantityAfter,
                referenceType = ReferenceType.LOT_EXPIRATION,
                referenceId = lotId,
                idempotencyKey = key,
                requesterService = RequesterService.of(SCHEDULER),
            ),
        )
    }

    private fun unchanged(lot: Lot): LotExpirationResult =
        LotExpirationResult(
            lotId = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" },
            productId = lot.productId,
            lotNumber = lot.lotNumber,
            expirationDate = lot.expirationDate,
            statusChanged = false,
            becameExpired = false,
            expired = lot.lotStatus == LotStatus.EXPIRED,
            transfers = emptyList(),
        )
}

private const val MAX_SCAN_SIZE: Int = 1000
private const val SCHEDULER: String = "scheduler"
