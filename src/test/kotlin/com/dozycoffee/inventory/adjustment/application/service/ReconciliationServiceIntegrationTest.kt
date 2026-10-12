package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationRunResult
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate

/** 실제 MySQL로 대사 배치의 보정안 생성, 실행 기록, 중복 방지, WMS 실패 처리를 검증한다. WMS는 포트를 대체한다 */
@InventoryIntegrationTest
class ReconciliationServiceIntegrationTest {
    @Autowired
    private lateinit var reconcileStockUseCase: ReconcileStockUseCase

    @MockitoBean
    private lateinit var wmsStockPort: WmsStockPort

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotA: Long = 0L
    private var lotB: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            lotA = checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId)
            lotB = checkNotNull(fixture.seedLot(productId, "B", LocalDate.of(2027, 6, 1)).lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun wms(
        lot: String,
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): WmsStockQuantity = WmsStockQuantity(productId, lot, quality, quantity)

    private suspend fun stubWms(
        warehouseId: Long,
        vararg items: WmsStockQuantity,
    ) {
        whenever(wmsStockPort.findQuantities(warehouseId, null, null)).thenReturn(items.toList())
    }

    private suspend fun scalar(sql: String): String =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()

    @Nested
    inner class `보정안 생성` {
        @Test
        fun `불일치한 행마다 처리 대기 보정안을 만들고 실행 기록을 남긴다`() =
            runBlocking<Unit> {
                val rowA: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100, reservedQuantity = 30)
                fixture.insertInventory(10L, productId, lotB, quantity = 20)
                stubWms(10L, wms("A", 92), wms("B", 20))

                // 스케줄러처럼 시스템 주체로 실행한다
                val results: List<ReconciliationRunResult> = ActorContext.with(SystemActor) { reconcileStockUseCase.reconcile() }

                assertThat(results.single().status).isEqualTo(ReconciliationStatus.COMPLETED)
                assertThat(results.single().checkedCount).isEqualTo(2)
                assertThat(results.single().mismatchCount).isEqualTo(1)
                assertThat(results.single().createdCount).isEqualTo(1)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment WHERE adjustment_type = 'RECONCILIATION'")).isEqualTo("1")
                assertThat(scalar("SELECT status FROM stock_adjustment")).isEqualTo("PENDING")
                assertThat(scalar("SELECT requested_by FROM stock_adjustment")).isEqualTo("system")
                assertThat(
                    scalar("SELECT reconciliation_run_id FROM stock_adjustment"),
                ).isEqualTo(results.single().reconciliationRunId.toString())
                assertThat(scalar("SELECT idempotency_key FROM stock_adjustment")).startsWith("reconciliation-10-")
                assertThat(scalar("SELECT inventory_quantity FROM stock_adjustment_item")).isEqualTo("100")
                assertThat(scalar("SELECT wms_quantity FROM stock_adjustment_item")).isEqualTo("92")
                assertThat(scalar("SELECT quantity_change FROM stock_adjustment_item")).isEqualTo("-8")
                assertThat(scalar("SELECT status FROM reconciliation_run")).isEqualTo("COMPLETED")
                assertThat(scalar("SELECT checked_count FROM reconciliation_run")).isEqualTo("2")
                assertThat(scalar("SELECT mismatch_count FROM reconciliation_run")).isEqualTo("1")
                assertThat(scalar("SELECT created_by FROM stock_adjustment")).isEqualTo("system")
                // 대사는 수량을 바꾸지 않는다
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $rowA")).isEqualTo("100")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("0")
            }

        @Test
        fun `같은 날 다시 실행하거나 처리 대기 보정안이 있으면 중복해서 만들지 않는다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(10L, wms("A", 92))

                val first: ReconciliationRunResult = reconcileStockUseCase.reconcile().single()
                val second: ReconciliationRunResult = reconcileStockUseCase.reconcile().single()

                assertThat(first.createdCount).isEqualTo(1)
                assertThat(second.mismatchCount).isEqualTo(1)
                assertThat(second.createdCount).isEqualTo(0)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("1")
                assertThat(scalar("SELECT COUNT(*) FROM reconciliation_run")).isEqualTo("2")
            }

        @Test
        fun `WMS에만 있는 Lot과 inventory에만 있는 행도 보정안이 되고 등록되지 않은 Lot은 건너뛴다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, "DEFECTIVE", quantity = 4)
                stubWms(10L, wms("B", 7), wms("UNKNOWN", 3))

                val result: ReconciliationRunResult = reconcileStockUseCase.reconcile().single()

                assertThat(result.checkedCount).isEqualTo(3)
                assertThat(result.mismatchCount).isEqualTo(2)
                assertThat(result.createdCount).isEqualTo(2)
                assertThat(scalar("SELECT quantity_change FROM stock_adjustment_item WHERE lot_id = $lotA")).isEqualTo("-4")
                assertThat(scalar("SELECT quantity_change FROM stock_adjustment_item WHERE lot_id = $lotB")).isEqualTo("7")
                assertThat(scalar("SELECT inventory_quantity FROM stock_adjustment_item WHERE lot_id = $lotB")).isEqualTo("0")
            }

        @Test
        fun `같은 배치가 동시에 여러 번 돌아도 보정안은 한 번만 만들어진다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                fixture.insertInventory(10L, productId, lotB, quantity = 50)
                stubWms(10L, wms("A", 92), wms("B", 45))

                (1..8).map { async(Dispatchers.Default) { reconcileStockUseCase.reconcile() } }.awaitAll()

                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("2")
                assertThat(scalar("SELECT COUNT(DISTINCT idempotency_key) FROM stock_adjustment")).isEqualTo("2")
            }
    }

    @Nested
    inner class `창고와 실패` {
        @Test
        fun `재고 행이 있는 창고마다 실행하고 한 창고의 WMS 실패는 그 실행만 실패로 남긴다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                fixture.insertInventory(20L, productId, lotA, quantity = 50)
                whenever(wmsStockPort.findQuantities(10L, null, null)).thenThrow(WmsUnavailableException())
                stubWms(20L, wms("A", 40))

                val results: List<ReconciliationRunResult> = reconcileStockUseCase.reconcile()

                assertThat(results.map { it.warehouseId to it.status })
                    .containsExactly(10L to ReconciliationStatus.FAILED, 20L to ReconciliationStatus.COMPLETED)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment WHERE warehouse_id = 10")).isEqualTo("0")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment WHERE warehouse_id = 20")).isEqualTo("1")
                assertThat(scalar("SELECT status FROM reconciliation_run WHERE warehouse_id = 10")).isEqualTo("FAILED")
                assertThat(scalar("SELECT finished_at IS NOT NULL FROM reconciliation_run WHERE warehouse_id = 10")).isIn("1", "true")
            }

        @Test
        fun `재고 행이 없으면 실행하지 않는다`() =
            runBlocking<Unit> {
                assertThat(reconcileStockUseCase.reconcile()).isEmpty()
                assertThat(scalar("SELECT COUNT(*) FROM reconciliation_run")).isEqualTo("0")
            }
    }
}
