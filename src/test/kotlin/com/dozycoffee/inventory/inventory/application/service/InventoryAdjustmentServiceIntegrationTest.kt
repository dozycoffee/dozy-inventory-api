package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAdjustedQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
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
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.LocalDate

/** 실제 MySQL로 실사 조정의 수량, 이력, 이벤트, 보류 해제, 롤백, 동시성을 검증한다. 호출자 트랜잭션은 테스트가 연다 */
@InventoryIntegrationTest
class InventoryAdjustmentServiceIntegrationTest {
    @Autowired
    private lateinit var adjustInventoryUseCase: AdjustInventoryUseCase

    @Autowired
    private lateinit var getAdjustedQuantitiesUseCase: GetAdjustedQuantitiesUseCase

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

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
        lotId: Long,
        change: Int,
        referenceId: Long,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): AdjustInventoryCommand.Item = AdjustInventoryCommand.Item(productId, lotId, quality, change, referenceId)

    private suspend fun adjust(
        key: String,
        vararg items: AdjustInventoryCommand.Item,
    ): AdjustInventoryResult =
        checkNotNull(
            transactionalOperator.executeAndAwait {
                adjustInventoryUseCase.adjust(AdjustInventoryCommand(10L, items.toList(), key, "svc-wms"))
            },
        )

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

    private suspend fun column(
        inventoryId: Long,
        column: String,
    ): Long = scalar("SELECT $column FROM inventory WHERE inventory_id = $inventoryId")

    @Nested
    inner class `반영` {
        @Test
        fun `행이 없는 Lot의 증가는 행을 만들고 ADJUSTMENT 이력과 증가 이벤트를 남긴다`() =
            runBlocking<Unit> {
                val result: AdjustInventoryResult = adjust("audit-1", item(lotA, 7, 31L))

                val created = result.items.single()
                assertThat(created.quantityAfter).isEqualTo(7)
                assertThat(column(created.inventoryId, "quantity")).isEqualTo(7L)
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'ADJUSTMENT' " +
                            "AND reference_type = 'STOCK_ADJUSTMENT_ITEM' " +
                            "AND reference_id = 31 AND quantity_change = 7 AND quantity_after = 7",
                    ),
                ).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_INCREASED'")).isEqualTo(1L)
            }

        @Test
        fun `감소는 총 수량을 줄이고 감소 이벤트를 남긴다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)

                val result: AdjustInventoryResult = adjust("audit-1", item(lotA, -4, 31L))

                assertThat(result.items.single().quantityAfter).isEqualTo(6)
                assertThat(column(row, "quantity")).isEqualTo(6L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_DECREASED'")).isEqualTo(1L)
            }

        @Test
        fun `불량과 폐기 예정 행도 조정할 수 있다`() =
            runBlocking<Unit> {
                val defective: Long = fixture.insertInventory(10L, productId, lotA, "DEFECTIVE", quantity = 5)
                val scheduled: Long = fixture.insertInventory(10L, productId, lotB, "DISPOSAL_SCHEDULED", quantity = 5)

                adjust(
                    "audit-1",
                    item(lotA, -2, 1L, QualityStatus.DEFECTIVE),
                    item(lotB, 3, 2L, QualityStatus.DISPOSAL_SCHEDULED),
                )

                assertThat(column(defective, "quantity")).isEqualTo(3L)
                assertThat(column(scheduled, "quantity")).isEqualTo(8L)
            }

        @Test
        fun `반영한 행의 할당 보류는 풀린다`() =
            runBlocking<Unit> {
                val held: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10, hold = true)
                val free: Long = fixture.insertInventory(10L, productId, lotB, quantity = 10)

                adjust("audit-1", item(lotA, -1, 1L), item(lotB, -1, 2L))

                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE inventory_id = $held AND allocation_hold = 0"))
                    .isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE inventory_id = $held AND held_at IS NULL AND hold_reason IS NULL"))
                    .isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE inventory_id = $free AND allocation_hold = 0"))
                    .isEqualTo(1L)
            }
    }

    @Nested
    inner class `거부` {
        @Test
        fun `예약 수량을 뺀 가용 수량을 넘는 감소는 거부하고 앞서 반영한 항목도 롤백된다`() =
            runBlocking<Unit> {
                val first: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)
                val second: Long = fixture.insertInventory(10L, productId, lotB, quantity = 10, reservedQuantity = 4)

                assertThrows<InsufficientAvailableQuantityException> {
                    adjust("audit-1", item(lotA, -3, 1L), item(lotB, -7, 2L))
                }

                assertThat(column(first, "quantity")).isEqualTo(10L)
                assertThat(column(second, "quantity")).isEqualTo(10L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(0L)
            }

        @Test
        fun `가용 수량과 같은 감소는 반영한다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10, reservedQuantity = 4)

                adjust("audit-1", item(lotA, -6, 1L))

                assertThat(column(row, "quantity")).isEqualTo(4L)
                assertThat(column(row, "reserved_quantity")).isEqualTo(4L)
            }

        @Test
        fun `행이 없는 감소는 재고 없음으로 거부한다`() =
            runBlocking<Unit> {
                assertThrows<InventoryNotFoundException> { adjust("audit-1", item(lotA, -1, 1L)) }
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 멱등 키로 다시 반영하면 중복 키로 거부하고 수량은 한 번만 바뀐다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)
                adjust("audit-1", item(lotA, 5, 31L))

                assertThrows<DuplicateIdempotencyKeyException> { adjust("audit-1", item(lotA, 5, 31L)) }

                assertThat(column(row, "quantity")).isEqualTo(15L)
            }

        @Test
        fun `저장된 이력으로 항목별 반영 후 수량을 다시 만든다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotA, quantity = 10)
                adjust("audit-1", item(lotA, 5, 31L), item(lotB, 3, 32L))

                assertThat(getAdjustedQuantitiesUseCase.getQuantitiesAfter("audit-1")).isEqualTo(mapOf(31L to 15, 32L to 3))
            }
    }

    @Nested
    inner class `동시성` {
        @Test
        fun `두 행을 서로 반대 순서로 동시에 조정해도 데드락 없이 모두 반영된다`() =
            runBlocking<Unit> {
                val first: Long = fixture.insertInventory(10L, productId, lotA, quantity = 1000)
                val second: Long = fixture.insertInventory(10L, productId, lotB, quantity = 1000)

                val results: List<Result<AdjustInventoryResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    val a = item(lotA, -1, 100L + i * 2)
                                    val b = item(lotB, -1, 101L + i * 2)
                                    if (i % 2 == 0) adjust("audit-$i", a, b) else adjust("audit-$i", b, a)
                                }
                            }
                        }.awaitAll()

                assertThat(results.filter { it.isFailure }).isEmpty()
                assertThat(column(first, "quantity")).isEqualTo(980L)
                assertThat(column(second, "quantity")).isEqualTo(980L)
            }

        @Test
        fun `같은 행의 감소가 동시에 몰려도 가용 수량을 넘겨 줄이지 않는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotA, quantity = 10)

                val results: List<Result<AdjustInventoryResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(Dispatchers.IO) { runCatching { adjust("audit-$i", item(lotA, -1, i.toLong())) } }
                        }.awaitAll()

                assertThat(results.count { it.isSuccess }).isEqualTo(10)
                assertThat(
                    results.filter { it.isFailure }.map { it.exceptionOrNull() },
                ).allMatch { it is InsufficientAvailableQuantityException }
                assertThat(column(row, "quantity")).isEqualTo(0L)
            }
    }
}
