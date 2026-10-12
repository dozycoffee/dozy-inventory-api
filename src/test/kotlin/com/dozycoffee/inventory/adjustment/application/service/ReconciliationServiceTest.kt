package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationRunResult
import com.dozycoffee.inventory.adjustment.application.port.out.ReconciliationRunRepository
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.FindWarehouseIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetWarehouseQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class ReconciliationServiceTest {
    @Mock
    private lateinit var findWarehouseIdsUseCase: FindWarehouseIdsUseCase

    @Mock
    private lateinit var getWarehouseQuantitiesUseCase: GetWarehouseQuantitiesUseCase

    @Mock
    private lateinit var getLotUseCase: GetLotUseCase

    @Mock
    private lateinit var wmsStockPort: WmsStockPort

    @Mock
    private lateinit var stockAdjustmentRepository: StockAdjustmentRepository

    @Mock
    private lateinit var reconciliationRunRepository: ReconciliationRunRepository

    private lateinit var service: ReconciliationService

    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")
    private val startedAt: LocalDateTime = LocalDateTime.of(2026, 10, 12, 1, 30)

    @BeforeEach
    fun setUp() {
        service =
            ReconciliationService(
                findWarehouseIdsUseCase,
                getWarehouseQuantitiesUseCase,
                getLotUseCase,
                wmsStockPort,
                stockAdjustmentRepository,
                reconciliationRunRepository,
                DirectTransactionalOperator(),
                Clock.fixed(Instant.parse("2026-10-11T16:30:00Z"), seoul),
            )
    }

    private fun row(
        lotId: Long,
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
        productId: Long = 100L,
    ): WarehouseQuantityResult = WarehouseQuantityResult(lotId * 10, productId, lotId, "LOT-$lotId", quality, quantity)

    private fun wms(
        lotId: Long,
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
        productId: Long = 100L,
    ): WmsStockQuantity = WmsStockQuantity(productId, "LOT-$lotId", quality, quantity)

    private suspend fun stubWarehouse(
        warehouseId: Long,
        inventory: List<WarehouseQuantityResult>,
        wmsItems: List<WmsStockQuantity>,
        pending: Set<Pair<Long, QualityStatus>> = emptySet(),
    ) {
        whenever(getWarehouseQuantitiesUseCase.getQuantities(warehouseId, null, null)).thenReturn(inventory)
        whenever(wmsStockPort.findQuantities(warehouseId, null, null)).thenReturn(wmsItems)
        whenever(stockAdjustmentRepository.findPendingReconciliationTargets(warehouseId)).thenReturn(pending)
    }

    private suspend fun stubRuns() {
        whenever(reconciliationRunRepository.save(any())).thenAnswer { invocation ->
            val run: ReconciliationRun = invocation.getArgument(0)
            ReconciliationRun.reconstitute(77L + run.warehouseId, run.warehouseId, ReconciliationStatus.RUNNING, run.startedAt, null, 0, 0)
        }
    }

    private suspend fun stubWarehouseIds(vararg ids: Long) {
        whenever(findWarehouseIdsUseCase.findWarehouseIds()).thenReturn(ids.toList())
    }

    private suspend fun savedProposals(count: Int): List<StockAdjustment> {
        val saved = argumentCaptor<StockAdjustment>()
        verifyBlocking(stockAdjustmentRepository, times(count)) { save(saved.capture()) }
        return saved.allValues
    }

    @Nested
    inner class `비교와 보정안 생성` {
        @Test
        fun `모두 일치하면 보정안을 만들지 않고 실행을 완료로 기록한다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 50), row(2L, 0, QualityStatus.DEFECTIVE)), listOf(wms(1L, 50)))

                val results: List<ReconciliationRunResult> = service.reconcile()

                assertEquals(listOf(ReconciliationRunResult(10L, 87L, ReconciliationStatus.COMPLETED, 2, 0, 0)), results)
                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
                val run = argumentCaptor<ReconciliationRun>()
                verifyBlocking(reconciliationRunRepository) { update(run.capture()) }
                assertEquals(ReconciliationStatus.COMPLETED, run.firstValue.status)
                assertEquals(2, run.firstValue.checkedCount)
                assertEquals(startedAt, run.firstValue.finishedAt)
            }

        @Test
        fun `수량이 다르면 WMS 수량에서 inventory 수량을 뺀 변동량으로 처리 대기 보정안을 만든다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 100), row(2L, 20)), listOf(wms(1L, 92), wms(2L, 20)))
                whenever(stockAdjustmentRepository.save(any())).thenAnswer { it.getArgument<StockAdjustment>(0) }

                val results: List<ReconciliationRunResult> = service.reconcile()

                assertEquals(ReconciliationRunResult(10L, 87L, ReconciliationStatus.COMPLETED, 2, 1, 1), results.single())
                val proposal: StockAdjustment = savedProposals(1).single()
                assertEquals(AdjustmentType.RECONCILIATION, proposal.adjustmentType)
                assertEquals(AdjustmentStatus.PENDING, proposal.status)
                assertEquals(10L, proposal.warehouseId)
                assertEquals(87L, proposal.reconciliationRunId)
                assertEquals("system", proposal.requestedBy.value)
                assertEquals("reconciliation-10-20261012-1-NORMAL", proposal.idempotencyKey.value)
                val item = proposal.items.single()
                assertEquals(1L, item.lotId)
                assertEquals(QualityStatus.NORMAL, item.qualityStatus)
                assertEquals(100, item.inventoryQuantity)
                assertEquals(92, item.wmsQuantity)
                assertEquals(-8, item.quantityChange)
            }

        @Test
        fun `WMS에 없는 inventory 행은 WMS 수량 0으로 보고 WMS에만 있는 행은 inventory 수량 0으로 본다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 30, QualityStatus.DEFECTIVE)), listOf(wms(2L, 7)))
                whenever(getLotUseCase.findByProductAndLotNumber(100L, "LOT-2")).thenReturn(LotResult(2L, 100L, "LOT-2", null, null))
                whenever(stockAdjustmentRepository.save(any())).thenAnswer { it.getArgument<StockAdjustment>(0) }

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(2, result.checkedCount)
                assertEquals(2, result.mismatchCount)
                assertEquals(2, result.createdCount)
                val byLot = savedProposals(2).associateBy { it.items.single().lotId }
                assertEquals(
                    -30,
                    byLot
                        .getValue(1L)
                        .items
                        .single()
                        .quantityChange,
                )
                assertEquals(
                    0,
                    byLot
                        .getValue(1L)
                        .items
                        .single()
                        .wmsQuantity,
                )
                assertEquals(
                    7,
                    byLot
                        .getValue(2L)
                        .items
                        .single()
                        .quantityChange,
                )
                assertEquals(
                    0,
                    byLot
                        .getValue(2L)
                        .items
                        .single()
                        .inventoryQuantity,
                )
                assertEquals("reconciliation-10-20261012-2-NORMAL", byLot.getValue(2L).idempotencyKey.value)
            }

        @Test
        fun `WMS에 같은 항목이 여러 번 오면 합쳐서 비교한다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 10)), listOf(wms(1L, 4), wms(1L, 6)))

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(0, result.mismatchCount)
            }

        @Test
        fun `품질 상태가 다르면 다른 행으로 비교한다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 10, QualityStatus.NORMAL)), listOf(wms(1L, 10, QualityStatus.DEFECTIVE)))
                whenever(getLotUseCase.findByProductAndLotNumber(100L, "LOT-1")).thenReturn(LotResult(1L, 100L, "LOT-1", null, null))
                whenever(stockAdjustmentRepository.save(any())).thenAnswer { it.getArgument<StockAdjustment>(0) }

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(2, result.mismatchCount)
                assertEquals(setOf(-10, 10), savedProposals(2).map { it.items.single().quantityChange }.toSet())
            }

        @Test
        fun `inventory에 등록되지 않은 Lot이 WMS에만 있으면 보정안을 만들지 않고 불일치로 세지 않는다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, emptyList(), listOf(wms(9L, 5)))
                whenever(getLotUseCase.findByProductAndLotNumber(100L, "LOT-9")).thenReturn(null)

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(ReconciliationStatus.COMPLETED, result.status)
                assertEquals(1, result.checkedCount)
                assertEquals(0, result.mismatchCount)
                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
            }

        @Test
        fun `같은 행에 처리 대기 보정안이 이미 있으면 불일치로 세지만 새로 만들지 않는다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 100)), listOf(wms(1L, 92)), pending = setOf(1L to QualityStatus.NORMAL))

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(1, result.mismatchCount)
                assertEquals(0, result.createdCount)
                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
            }

        @Test
        fun `같은 날 같은 행의 보정안이 이미 있어 멱등 키가 겹치면 만들지 않고 계속한다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L)
                stubRuns()
                stubWarehouse(10L, listOf(row(1L, 100), row(2L, 100)), listOf(wms(1L, 92), wms(2L, 91)))
                whenever(stockAdjustmentRepository.save(any()))
                    .thenThrow(DuplicateAdjustmentKeyException())
                    .thenAnswer { it.getArgument<StockAdjustment>(0) }

                val result: ReconciliationRunResult = service.reconcile().single()

                assertEquals(ReconciliationStatus.COMPLETED, result.status)
                assertEquals(2, result.mismatchCount)
                assertEquals(1, result.createdCount)
            }
    }

    @Nested
    inner class `창고별 실행` {
        @Test
        fun `재고 행이 있는 창고마다 실행하고 한 창고의 WMS 조회 실패는 그 실행만 실패로 기록한다`() =
            runBlocking<Unit> {
                stubWarehouseIds(10L, 20L)
                stubRuns()
                whenever(getWarehouseQuantitiesUseCase.getQuantities(10L, null, null)).thenReturn(listOf(row(1L, 5)))
                whenever(wmsStockPort.findQuantities(10L, null, null)).thenThrow(WmsUnavailableException())
                stubWarehouse(20L, listOf(row(2L, 5)), listOf(wms(2L, 5)))

                val results: List<ReconciliationRunResult> = service.reconcile()

                assertEquals(listOf(ReconciliationStatus.FAILED, ReconciliationStatus.COMPLETED), results.map { it.status })
                assertEquals(listOf(10L, 20L), results.map { it.warehouseId })
                assertEquals(0, results.first().checkedCount)
                val updated = argumentCaptor<ReconciliationRun>()
                verifyBlocking(reconciliationRunRepository, times(2)) { update(updated.capture()) }
                assertEquals(ReconciliationStatus.FAILED, updated.firstValue.status)
                assertEquals(startedAt, updated.firstValue.finishedAt)
            }

        @Test
        fun `재고 행이 있는 창고가 없으면 아무것도 하지 않는다`() =
            runBlocking<Unit> {
                stubWarehouseIds()

                assertEquals(emptyList<ReconciliationRunResult>(), service.reconcile())

                verifyBlocking(reconciliationRunRepository, never()) { save(any()) }
            }
    }
}
