package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationRunResult
import com.dozycoffee.inventory.adjustment.application.port.out.ReconciliationRunRepository
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.FindWarehouseIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetWarehouseQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 대사 배치(ADR-0033). 창고마다 실행을 기록하고 inventory 총 수량과 WMS 수량을 `상품 × Lot 번호 × 품질 상태` 단위로 비교해
 * 불일치한 행마다 처리 대기 보정안을 만든다. 보정안은 같은 날 같은 행이면 같은 멱등 키라 여러 번 실행하거나 인스턴스가 겹쳐도 중복되지 않고,
 * 같은 행에 처리 대기 보정안이 이미 있으면 새로 만들지 않는다(승인할 때 최신 수량으로 다시 계산한다). 보정안은 하나씩 따로 저장해
 * 일부가 실패해도 나머지가 남는다. WMS에만 있고 inventory에 Lot이 등록되지 않은 행은 보정안을 만들 수 없어 경고 로그만 남긴다.
 */
@Service
class ReconciliationService(
    private val findWarehouseIdsUseCase: FindWarehouseIdsUseCase,
    private val getWarehouseQuantitiesUseCase: GetWarehouseQuantitiesUseCase,
    private val getLotUseCase: GetLotUseCase,
    private val wmsStockPort: WmsStockPort,
    private val stockAdjustmentRepository: StockAdjustmentRepository,
    private val reconciliationRunRepository: ReconciliationRunRepository,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : ReconcileStockUseCase {
    override suspend fun reconcile(): List<ReconciliationRunResult> =
        findWarehouseIdsUseCase.findWarehouseIds().map { warehouseId: Long -> reconcileWarehouse(warehouseId) }

    private suspend fun reconcileWarehouse(warehouseId: Long): ReconciliationRunResult {
        val run: ReconciliationRun =
            checkNotNull(
                transactionalOperator.executeAndAwait { reconciliationRunRepository.save(ReconciliationRun.start(warehouseId, now())) },
            ) {
                "대사 실행 저장 결과가 없다"
            }
        val runId: Long = checkNotNull(run.reconciliationRunId) { "저장된 대사 실행은 식별자가 있어야 한다" }
        return try {
            // (비교한 항목 수, 불일치 수, 새로 만든 보정안 수)
            val (checked: Int, mismatches: Int, created: Int) = compareAndPropose(warehouseId, runId)
            run.complete(checked, mismatches, now())
            reconciliationRunRepository.update(run)
            log.info("대사 완료: 창고 {}, 비교 {}건, 불일치 {}건, 보정안 {}건 생성", warehouseId, checked, mismatches, created)
            ReconciliationRunResult(warehouseId, runId, run.status, checked, mismatches, created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("대사에 실패했다: 창고 {}", warehouseId, e)
            run.fail(now())
            reconciliationRunRepository.update(run)
            ReconciliationRunResult(warehouseId, runId, run.status, 0, 0, 0)
        }
    }

    private suspend fun compareAndPropose(
        warehouseId: Long,
        runId: Long,
    ): Triple<Int, Int, Int> {
        val inventory: Map<RowKey, WarehouseQuantityResult> =
            getWarehouseQuantitiesUseCase.getQuantities(warehouseId, null, null).associateBy {
                RowKey(it.productId, it.lotNumber, it.qualityStatus)
            }
        val wms: Map<RowKey, Int> =
            wmsStockPort
                .findQuantities(warehouseId, null, null)
                .groupBy { RowKey(it.productId, it.lotNumber, it.qualityStatus) }
                .mapValues { (_, items: List<WmsStockQuantity>) -> items.sumOf { it.quantity } }

        val keys: Set<RowKey> = inventory.keys + wms.keys
        val mismatched: List<RowKey> = keys.filter { (inventory[it]?.quantity ?: 0) != (wms[it] ?: 0) }.sortedWith(ROW_ORDER)
        val pending: Set<Pair<Long, QualityStatus>> = stockAdjustmentRepository.findPendingReconciliationTargets(warehouseId)

        var mismatches = 0
        var created = 0
        for (key: RowKey in mismatched) {
            val lotId: Long = inventory[key]?.lotId ?: findLotId(key) ?: continue
            mismatches++
            if (lotId to key.third in pending) continue
            val item: StockAdjustmentItem =
                StockAdjustmentItem.reconciliation(lotId, key.third, inventory[key]?.quantity ?: 0, wms[key] ?: 0)
            if (save(warehouseId, runId, item)) created++
        }
        return Triple(keys.size, mismatches, created)
    }

    /** WMS에만 있는 행의 Lot을 찾는다. inventory에 등록되지 않은 Lot이면 보정안을 만들 수 없어 경고만 남기고 null을 반환한다 */
    private suspend fun findLotId(key: RowKey): Long? {
        val lot: LotResult? = getLotUseCase.findByProductAndLotNumber(key.first, key.second)
        if (lot == null) {
            log.warn("WMS에만 있고 inventory에 등록되지 않은 Lot이라 보정안을 만들지 못했다: 상품 {}, Lot {}, {}", key.first, key.second, key.third)
        }
        return lot?.lotId
    }

    /** 보정안 하나를 저장한다. 같은 날 같은 행의 보정안이 이미 있으면(같은 멱등 키) 만들지 않고 false를 반환한다 */
    private suspend fun save(
        warehouseId: Long,
        runId: Long,
        item: StockAdjustmentItem,
    ): Boolean {
        val key: IdempotencyKey =
            IdempotencyKey.of(
                "reconciliation-$warehouseId-${today().format(BASIC_DATE)}-${item.lotId}-${item.qualityStatus.name}",
            )
        val proposal: StockAdjustment = StockAdjustment.reconciliation(warehouseId, runId, item, RequesterService.of(SYSTEM), key)
        return try {
            transactionalOperator.executeAndAwait { stockAdjustmentRepository.save(proposal) }
            true
        } catch (e: DuplicateAdjustmentKeyException) {
            false
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)

    private fun today(): LocalDate = LocalDate.now(clock)
}

/** 비교 키: (상품 ID, Lot 번호, 품질 상태). application/service에는 접미사 규칙 때문에 별도 클래스를 두지 않는다 */
private typealias RowKey = Triple<Long, String, QualityStatus>

private val ROW_ORDER: Comparator<RowKey> = compareBy({ it.first }, { it.second }, { it.third })
private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
private const val SYSTEM: String = "system"
private val log: Logger = LoggerFactory.getLogger(ReconciliationService::class.java)
