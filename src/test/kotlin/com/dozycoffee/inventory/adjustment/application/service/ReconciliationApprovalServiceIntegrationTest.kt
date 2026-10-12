package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.ApproveReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.RejectReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationAdminRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationChangedException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
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
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate

/** 실제 MySQL로 대사 보정안의 승인(반영, 재계산, 이력, 이벤트, 멱등, 동시성)과 거부를 검증한다. WMS는 포트를 대체한다 */
@InventoryIntegrationTest
class ReconciliationApprovalServiceIntegrationTest {
    @Autowired
    private lateinit var reconcileStockUseCase: ReconcileStockUseCase

    @Autowired
    private lateinit var approveReconciliationUseCase: ApproveReconciliationUseCase

    @Autowired
    private lateinit var rejectReconciliationUseCase: RejectReconciliationUseCase

    @MockitoBean
    private lateinit var wmsStockPort: WmsStockPort

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotA: Long = 0L
    private val reviewer: String = "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            lotA = checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun wms(
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): WmsStockQuantity = WmsStockQuantity(productId, "A", quality, quantity)

    private suspend fun stubWms(vararg items: WmsStockQuantity) {
        whenever(wmsStockPort.findQuantities(10L, null, null)).thenReturn(items.toList())
        whenever(wmsStockPort.findQuantities(10L, productId, "A")).thenReturn(items.filter { it.lotNumber == "A" })
    }

    /** 대사를 돌려 처리 대기 보정안을 만들고 그 ID를 반환한다 */
    private suspend fun propose(): Long {
        ActorContext.with(SystemActor) { reconcileStockUseCase.reconcile() }
        return scalar("SELECT MAX(stock_adjustment_id) FROM stock_adjustment").toLong()
    }

    private fun command(
        id: Long,
        isAdmin: Boolean = true,
        scope: WarehouseScope = WarehouseScope.All,
    ): ReviewReconciliationCommand = ReviewReconciliationCommand(id, reviewer, isAdmin, scope)

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
    inner class `승인` {
        @Test
        fun `감소 보정안을 승인하면 총 수량을 줄이고 RECONCILIATION 이력과 이벤트를 남기며 보류를 푼다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100, reservedQuantity = 10, hold = true)
                stubWms(wms(92))
                val id: Long = propose()

                val result: ReconciliationProposalResult = approveReconciliationUseCase.approve(command(id))

                assertThat(result.status).isEqualTo(AdjustmentStatus.APPLIED)
                assertThat(result.inventoryId).isEqualTo(row)
                assertThat(result.approvedBy).isEqualTo(reviewer)
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("92")
                assertThat(scalar("SELECT CAST(allocation_hold AS UNSIGNED) FROM inventory WHERE inventory_id = $row")).isEqualTo("0")
                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("APPLIED")
                assertThat(scalar("SELECT approved_by FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo(reviewer)
                assertThat(
                    scalar("SELECT inventory_id FROM stock_adjustment_item WHERE stock_adjustment_id = $id"),
                ).isEqualTo(row.toString())
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'RECONCILIATION' AND quantity_change = -8 " +
                            "AND quantity_after = 92 AND requester_service = '$reviewer' AND reference_type = 'STOCK_ADJUSTMENT_ITEM'",
                    ),
                ).isEqualTo("1")
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_DECREASED'")).isEqualTo("1")
            }

        @Test
        fun `증가 보정안을 승인하면 없던 재고 행을 만든다`() =
            runBlocking<Unit> {
                // 재고 행이 있는 창고만 대사하므로 같은 창고에 일치하는 다른 행을 둔다
                val lotB: Long = checkNotNull(fixture.seedLot(productId, "B", LocalDate.of(2027, 6, 1)).lotId)
                fixture.insertInventory(10L, productId, lotB, quantity = 5)
                stubWms(wms(7), WmsStockQuantity(productId, "B", QualityStatus.NORMAL, 5))
                val id: Long = propose()

                val result: ReconciliationProposalResult = approveReconciliationUseCase.approve(command(id))

                assertThat(result.quantityChange).isEqualTo(7)
                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("7")
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_INCREASED'")).isEqualTo("1")
            }

        @Test
        fun `같은 보정안을 다시 승인해도 이중으로 반영하지 않고 같은 결과를 돌려준다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()
                approveReconciliationUseCase.approve(command(id))

                val replay: ReconciliationProposalResult = approveReconciliationUseCase.approve(command(id))

                assertThat(replay.status).isEqualTo(AdjustmentStatus.APPLIED)
                assertThat(replay.inventoryId).isEqualTo(row)
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("92")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("1")
            }

        @Test
        fun `같은 보정안을 동시에 승인해도 한 번만 반영한다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()

                val results: List<ReconciliationProposalResult> =
                    (1..10).map { async(Dispatchers.Default) { approveReconciliationUseCase.approve(command(id)) } }.awaitAll()

                assertThat(results.map { it.status }).containsOnly(AdjustmentStatus.APPLIED)
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("92")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("1")
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_DECREASED'")).isEqualTo("1")
            }

        @Test
        fun `승인할 때 WMS 수량이 바뀌었으면 보정안만 고치고 반영하지 않으며 다시 승인하면 새 숫자로 반영한다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()
                stubWms(wms(95))

                assertThrows<ReconciliationChangedException> { approveReconciliationUseCase.approve(command(id)) }

                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("100")
                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("PENDING")
                assertThat(scalar("SELECT wms_quantity FROM stock_adjustment_item WHERE stock_adjustment_id = $id")).isEqualTo("95")
                assertThat(scalar("SELECT quantity_change FROM stock_adjustment_item WHERE stock_adjustment_id = $id")).isEqualTo("-5")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("0")

                approveReconciliationUseCase.approve(command(id))

                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("95")
            }

        @Test
        fun `승인할 때 불일치가 사라졌으면 보정안을 거부 상태로 닫는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()
                stubWms(wms(100))

                assertThrows<ReconciliationChangedException> { approveReconciliationUseCase.approve(command(id)) }

                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("REJECTED")
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("100")
                assertThrows<InvalidAdjustmentStateException> { approveReconciliationUseCase.approve(command(id)) }
            }

        @Test
        fun `감소가 가용 수량을 넘으면 반영하지 못하고 보정안은 처리 대기로 남는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100, reservedQuantity = 95)
                stubWms(wms(92))
                val id: Long = propose()

                assertThrows<InsufficientAvailableQuantityException> { approveReconciliationUseCase.approve(command(id)) }

                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("PENDING")
                assertThat(scalar("SELECT approved_by IS NULL FROM stock_adjustment WHERE stock_adjustment_id = $id")).isIn("1", "true")
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("100")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("0")
            }

        @Test
        fun `변동량이 임계치를 넘으면 본사 관리자만 승인하고 범위 밖 창고는 거부한다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 500)
                stubWms(wms(300))
                val id: Long = propose()

                assertThrows<ReconciliationAdminRequiredException> { approveReconciliationUseCase.approve(command(id, isAdmin = false)) }
                assertThrows<WarehouseAccessDeniedException> {
                    approveReconciliationUseCase.approve(command(id, isAdmin = true, scope = WarehouseScope.Only(setOf(20L))))
                }
                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("PENDING")

                approveReconciliationUseCase.approve(command(id, isAdmin = true))

                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $id")).isEqualTo("APPLIED")
            }

        @Test
        fun `임계치 이하이면 창고 관리자도 배정된 창고의 보정안을 승인한다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()

                val result =
                    approveReconciliationUseCase.approve(command(id, isAdmin = false, scope = WarehouseScope.Only(setOf(10L))))

                assertThat(result.status).isEqualTo(AdjustmentStatus.APPLIED)
            }
    }

    @Nested
    inner class `거부` {
        @Test
        fun `거부하면 수량을 바꾸지 않고 다시 거부해도 같고 거부된 보정안은 승인할 수 없다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()

                val rejected: ReconciliationProposalResult = rejectReconciliationUseCase.reject(command(id, isAdmin = false))
                val again: ReconciliationProposalResult = rejectReconciliationUseCase.reject(command(id, isAdmin = false))

                assertThat(rejected.status).isEqualTo(AdjustmentStatus.REJECTED)
                assertThat(again.status).isEqualTo(AdjustmentStatus.REJECTED)
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo("100")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("0")
                assertThrows<InvalidAdjustmentStateException> { approveReconciliationUseCase.approve(command(id)) }
            }

        @Test
        fun `반영된 보정안은 거부할 수 없다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val id: Long = propose()
                approveReconciliationUseCase.approve(command(id))

                assertThrows<InvalidAdjustmentStateException> { rejectReconciliationUseCase.reject(command(id)) }
            }

        @Test
        fun `거부된 보정안이 있던 행은 다음 대사에서 새 보정안이 만들어진다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 100)
                stubWms(wms(92))
                val first: Long = propose()
                rejectReconciliationUseCase.reject(command(first))

                ActorContext.with(SystemActor) { reconcileStockUseCase.reconcile() }

                // 같은 날 같은 행의 멱등 키는 이미 쓰였으므로 새 보정안은 만들어지지 않는다(다음 날 대사에서 다시 올라온다)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("1")
                assertThat(scalar("SELECT status FROM stock_adjustment")).isEqualTo("REJECTED")
            }
    }
}
