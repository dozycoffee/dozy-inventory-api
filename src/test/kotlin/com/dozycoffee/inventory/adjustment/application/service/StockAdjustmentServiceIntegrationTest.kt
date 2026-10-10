package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.RequestStockAdjustmentUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.ApprovalRequiredException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate

/** 실제 MySQL로 실사 조정 요청의 승인 확인, 반영, 롤백, 멱등, 동시 요청을 검증한다. 서비스가 트랜잭션을 직접 연다 */
@InventoryIntegrationTest
class StockAdjustmentServiceIntegrationTest {
    @Autowired
    private lateinit var requestStockAdjustmentUseCase: RequestStockAdjustmentUseCase

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

    private fun item(
        lotNumber: String,
        change: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): RequestStockAdjustmentCommand.Item = RequestStockAdjustmentCommand.Item(productId, lotNumber, quality, change)

    private fun command(
        key: String = "audit-1",
        approvedBy: String? = null,
        vararg items: RequestStockAdjustmentCommand.Item,
    ): RequestStockAdjustmentCommand = RequestStockAdjustmentCommand(10L, 77L, items.toList(), approvedBy, key, "svc-wms")

    private suspend fun scalar(sql: String): Long =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()
            .toLong()

    private suspend fun quantity(inventoryId: Long): Long = scalar("SELECT quantity FROM inventory WHERE inventory_id = $inventoryId")

    @Nested
    inner class `반영` {
        @Test
        fun `조정과 항목을 저장하고 재고와 이력과 이벤트에 반영하며 항목에 재고 행을 기록한다`() =
            runBlocking<Unit> {
                val existing: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)

                val result: StockAdjustmentResult =
                    requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -4), item("B", 7))))

                assertThat(result.status).isEqualTo(AdjustmentStatus.APPLIED)
                assertThat(result.items.map { it.quantityChange }).containsExactly(-4, 7)
                assertThat(result.items.map { it.quantityAfter }).containsExactly(6, 7)
                assertThat(result.items.first().inventoryId).isEqualTo(existing)
                assertThat(quantity(existing)).isEqualTo(6L)
                assertThat(
                    scalar("SELECT COUNT(*) FROM stock_adjustment WHERE status = 'APPLIED' AND external_reference_id = 77"),
                ).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment_item WHERE inventory_id IS NOT NULL")).isEqualTo(2L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history WHERE history_type = 'ADJUSTMENT'")).isEqualTo(2L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type LIKE 'INVENTORY_%'")).isEqualTo(2L)
            }

        @Test
        fun `반영한 행의 할당 보류가 풀린다`() =
            runBlocking<Unit> {
                val held: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10, hold = true)

                requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -1))))

                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE inventory_id = $held AND allocation_hold = 0")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `승인` {
        @Test
        fun `임계치를 넘는 변동에 승인자가 없으면 거부하고 아무것도 남지 않는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 1000)

                assertThrows<ApprovalRequiredException> { requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -101)))) }

                assertThat(quantity(row)).isEqualTo(1000L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `임계치와 같은 변동은 승인자 없이 반영한다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 1000)

                requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -100))))

                assertThat(quantity(row)).isEqualTo(900L)
            }

        @Test
        fun `승인자가 있으면 임계치를 넘어도 반영하고 승인자를 기록한다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 1000)

                val result: StockAdjustmentResult =
                    requestStockAdjustmentUseCase.request(command(approvedBy = "manager-7", items = arrayOf(item("A", -500))))

                assertThat(result.approvedBy).isEqualTo("manager-7")
                assertThat(quantity(row)).isEqualTo(500L)
                assertThat(
                    scalar("SELECT COUNT(*) FROM stock_adjustment WHERE approved_by = 'manager-7' AND approved_at IS NOT NULL"),
                ).isEqualTo(1L)
            }
    }

    @Nested
    inner class `거부와 롤백` {
        @Test
        fun `가용 수량을 넘는 감소는 거부하고 조정과 앞서 반영한 항목이 모두 되돌아간다`() =
            runBlocking<Unit> {
                val first: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)
                val second: Long = fixture.insertInventory(10L, productId, lotB, quantity = 10, reservedQuantity = 8)

                assertThrows<InsufficientAvailableQuantityException> {
                    requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -3), item("B", -5))))
                }

                assertThat(quantity(first)).isEqualTo(10L)
                assertThat(quantity(second)).isEqualTo(10L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment_item")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(0L)
            }

        @Test
        fun `등록되지 않은 Lot이면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<LotNotFoundException> { requestStockAdjustmentUseCase.request(command(items = arrayOf(item("NOPE", 5)))) }

                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(0L)
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 멱등 키의 재요청은 수량을 다시 바꾸지 않고 처음과 같은 결과를 준다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)
                val first: StockAdjustmentResult = requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -4))))

                val again: StockAdjustmentResult = requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -4))))

                assertThat(again).isEqualTo(first)
                assertThat(quantity(row)).isEqualTo(6L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(1L)
            }

        @Test
        fun `재요청 사이에 재고가 바뀌어도 처음 결과의 반영 후 수량을 준다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 10)
                val first: StockAdjustmentResult = requestStockAdjustmentUseCase.request(command("audit-1", null, item("A", -4)))
                requestStockAdjustmentUseCase.request(command("audit-2", null, item("A", -3)))

                val again: StockAdjustmentResult = requestStockAdjustmentUseCase.request(command("audit-1", null, item("A", -4)))

                assertThat(again.items.single().quantityAfter).isEqualTo(first.items.single().quantityAfter)
                assertThat(again.items.single().quantityAfter).isEqualTo(6)
            }

        @Test
        fun `같은 멱등 키에 다른 내용이 오면 거부한다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 10)
                requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -4))))

                assertThrows<IdempotencyKeyConflictException> {
                    requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -5))))
                }
            }
    }

    @Nested
    inner class `동시성` {
        @Test
        fun `같은 멱등 키 20개가 동시에 들어와도 한 번만 반영하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 100)

                val results: List<StockAdjustmentResult> =
                    (1..20)
                        .map { async(Dispatchers.IO) { requestStockAdjustmentUseCase.request(command(items = arrayOf(item("A", -4)))) } }
                        .awaitAll()

                assertThat(results.toSet()).hasSize(1)
                assertThat(quantity(row)).isEqualTo(96L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history WHERE history_type = 'ADJUSTMENT'")).isEqualTo(1L)
            }

        @Test
        fun `서로 다른 키의 감소가 같은 행에 몰려도 가용 수량을 넘겨 줄이지 않는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)

                val results: List<Result<StockAdjustmentResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(
                                Dispatchers.IO,
                            ) { runCatching { requestStockAdjustmentUseCase.request(command("audit-$i", null, item("A", -1))) } }
                        }.awaitAll()

                assertThat(results.count { it.isSuccess }).isEqualTo(10)
                assertThat(
                    results.filter { it.isFailure }.map { it.exceptionOrNull() },
                ).allMatch { it is InsufficientAvailableQuantityException }
                assertThat(quantity(row)).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo(10L)
            }
    }
}
