package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
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
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@DataR2dbcTest
@ActiveProfiles("local")
@Import(
    StockAdjustmentPersistenceAdapter::class,
    LotPersistenceAdapter::class,
    R2dbcConfig::class,
    ClockConfig::class,
    LocalActorProvider::class,
)
class StockAdjustmentPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: StockAdjustmentPersistenceAdapter

    @Autowired
    private lateinit var lotAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private lateinit var fixture: InventoryDbFixture
    private var lotA: Long = 0L
    private var lotB: Long = 0L
    private var inventoryA: Long = 0L
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 10, 12, 0)

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotAdapter)
            fixture.cleanUp()
            val productId: Long = fixture.seedProduct()
            lotA = checkNotNull(fixture.seedLot(productId, "A").lotId)
            lotB = checkNotNull(fixture.seedLot(productId, "B").lotId)
            inventoryA = fixture.insertInventory(10L, productId, lotA)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun adjustment(
        key: String = "wms-audit-1",
        approvedBy: String? = "manager-7",
        vararg items: StockAdjustmentItem = arrayOf(StockAdjustmentItem.create(lotA, QualityStatus.NORMAL, 5)),
    ): StockAdjustment =
        StockAdjustment.audit(10L, 77L, items.toList(), RequesterService.of("svc-wms"), approvedBy, IdempotencyKey.of(key), now)

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
    inner class `저장과 조회` {
        @Test
        fun `조정과 항목을 함께 저장하고 식별자를 채운다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment =
                    adapter.save(
                        adjustment(
                            items =
                                arrayOf(
                                    StockAdjustmentItem.create(lotA, QualityStatus.NORMAL, 5),
                                    StockAdjustmentItem.create(lotB, QualityStatus.DEFECTIVE, -2),
                                ),
                        ),
                    )

                assertThat(saved.stockAdjustmentId).isNotNull()
                assertThat(saved.items).hasSize(2)
                assertThat(saved.items.map { it.stockAdjustmentItemId }).doesNotContainNull()
                assertThat(saved.items.map { it.quantityChange }).containsExactly(5, -2)
                assertThat(saved.items.map { it.inventoryId }).containsOnlyNulls()
            }

        @Test
        fun `저장한 조정을 ID와 멱등 키로 다시 읽는다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(adjustment())

                val byId: StockAdjustment = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId)))
                val byKey: StockAdjustment = checkNotNull(adapter.findByIdempotencyKey(IdempotencyKey.of("wms-audit-1")))

                assertThat(byKey).isEqualTo(byId)
                assertThat(byId.adjustmentType).isEqualTo(AdjustmentType.AUDIT)
                assertThat(byId.status).isEqualTo(AdjustmentStatus.APPLIED)
                assertThat(byId.warehouseId).isEqualTo(10L)
                assertThat(byId.externalReferenceId).isEqualTo(77L)
                assertThat(byId.requestedBy).isEqualTo(RequesterService.of("svc-wms"))
                assertThat(byId.approvedBy).isEqualTo("manager-7")
                assertThat(byId.approvedAt).isEqualTo(now)
                assertThat(byId.items.single().lotId).isEqualTo(lotA)
                assertThat(byId.items.single().qualityStatus).isEqualTo(QualityStatus.NORMAL)
            }

        @Test
        fun `승인자가 없는 조정은 승인 정보가 비어 있다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(adjustment(approvedBy = null))

                val loaded: StockAdjustment = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId)))

                assertThat(loaded.approvedBy).isNull()
                assertThat(loaded.approvedAt).isNull()
            }

        @Test
        fun `없는 조정은 null이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findById(999_999L)).isNull()
                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("nope"))).isNull()
            }

        @Test
        fun `같은 멱등 키로 다시 저장하면 중복으로 거부하고 하나만 남는다`() =
            runBlocking<Unit> {
                adapter.save(adjustment())

                assertThrows<DuplicateAdjustmentKeyException> { adapter.save(adjustment()) }

                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("1")
            }

        @Test
        fun `이미 저장된 조정은 다시 저장할 수 없다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(adjustment())

                assertThrows<IllegalStateException> { adapter.save(saved) }
            }
    }

    @Nested
    inner class `대상 재고 행 기록` {
        @Test
        fun `항목별 재고 행 ID를 기록한다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment =
                    adapter.save(
                        adjustment(
                            items =
                                arrayOf(
                                    StockAdjustmentItem.create(lotA, QualityStatus.NORMAL, 5),
                                    StockAdjustmentItem.create(lotB, QualityStatus.NORMAL, 3),
                                ),
                        ),
                    )
                val (first, second) = saved.items

                adapter.assignInventoryIds(mapOf(checkNotNull(first.stockAdjustmentItemId) to inventoryA))

                val loaded: StockAdjustment = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId)))
                assertThat(loaded.items.map { it.inventoryId }).containsExactly(inventoryA, null)
                assertThat(second.inventoryId).isNull()
            }
    }

    @Nested
    inner class `대사 보정안` {
        private fun proposal(
            key: String = "reconciliation-10-20261012-1-NORMAL",
            lotId: Long = lotA,
            quality: QualityStatus = QualityStatus.NORMAL,
            inventoryQuantity: Int = 100,
            wmsQuantity: Int = 92,
            warehouseId: Long = 10L,
        ): StockAdjustment =
            StockAdjustment.reconciliation(
                warehouseId,
                1L,
                StockAdjustmentItem.reconciliation(lotId, quality, inventoryQuantity, wmsQuantity),
                RequesterService.of("system"),
                IdempotencyKey.of(key),
            )

        @BeforeEach
        fun seedRun() =
            runBlocking<Unit> {
                databaseClient
                    .sql(
                        """
                        INSERT INTO reconciliation_run (reconciliation_run_id, warehouse_id, status, started_at, created_at, created_by, updated_at, updated_by)
                        VALUES (1, 10, 'RUNNING', NOW(6), NOW(6), 'test', NOW(6), 'test'), (2, 20, 'RUNNING', NOW(6), NOW(6), 'test', NOW(6), 'test')
                        """,
                    ).fetch()
                    .rowsUpdated()
                    .awaitFirst()
            }

        @Test
        fun `보정안을 대사 실행과 두 수량과 함께 저장하고 다시 읽는다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(proposal())

                val loaded: StockAdjustment = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId)))

                assertThat(loaded.adjustmentType).isEqualTo(AdjustmentType.RECONCILIATION)
                assertThat(loaded.status).isEqualTo(AdjustmentStatus.PENDING)
                assertThat(loaded.reconciliationRunId).isEqualTo(1L)
                assertThat(loaded.externalReferenceId).isNull()
                assertThat(loaded.requestedBy).isEqualTo(RequesterService.of("system"))
                assertThat(loaded.approvedBy).isNull()
                val item: StockAdjustmentItem = loaded.items.single()
                assertThat(item.inventoryQuantity).isEqualTo(100)
                assertThat(item.wmsQuantity).isEqualTo(92)
                assertThat(item.quantityChange).isEqualTo(-8)
            }

        @Test
        fun `같은 멱등 키의 보정안은 중복으로 거부한다`() =
            runBlocking<Unit> {
                adapter.save(proposal())

                assertThrows<DuplicateAdjustmentKeyException> { adapter.save(proposal()) }
            }

        @Test
        fun `창고의 처리 대기 보정안이 있는 Lot과 품질 상태를 찾고 다른 창고나 끝난 보정안은 제외한다`() =
            runBlocking<Unit> {
                val pending: StockAdjustment = adapter.save(proposal("k-1", lotA, QualityStatus.NORMAL))
                adapter.save(proposal("k-2", lotB, QualityStatus.DEFECTIVE))
                adapter.save(proposal("k-3", lotA, QualityStatus.DISPOSAL_SCHEDULED, warehouseId = 20L))
                val done: StockAdjustment = adapter.save(proposal("k-4", lotB, QualityStatus.NORMAL))
                done.reject()
                adapter.updateState(done, AdjustmentStatus.PENDING)
                adapter.save(adjustment("audit-1"))

                assertThat(adapter.findPendingReconciliationTargets(10L))
                    .containsExactlyInAnyOrder(lotA to QualityStatus.NORMAL, lotB to QualityStatus.DEFECTIVE)
                assertThat(pending.stockAdjustmentId).isNotNull()
                assertThat(adapter.findPendingReconciliationTargets(20L)).containsExactly(lotA to QualityStatus.DISPOSAL_SCHEDULED)
                assertThat(adapter.findPendingReconciliationTargets(99L)).isEmpty()
            }

        @Test
        fun `기대한 상태일 때만 상태와 승인 정보를 바꾸고 아니면 false다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(proposal())
                saved.approve("manager-1", now)

                assertThat(adapter.updateState(saved, AdjustmentStatus.PENDING)).isTrue()

                val loaded: StockAdjustment = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId)))
                assertThat(loaded.status).isEqualTo(AdjustmentStatus.APPLIED)
                assertThat(loaded.approvedBy).isEqualTo("manager-1")
                assertThat(loaded.approvedAt).isEqualTo(now)
                assertThat(adapter.updateState(saved, AdjustmentStatus.PENDING)).isFalse()
            }

        @Test
        fun `같은 보정안을 동시에 승인하려 하면 정확히 하나만 성공한다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(proposal())
                val id: Long = checkNotNull(saved.stockAdjustmentId)

                val results: List<Boolean> =
                    (1..20)
                        .map {
                            async(Dispatchers.Default) {
                                val copy: StockAdjustment = checkNotNull(adapter.findById(id))
                                copy.approve("manager-$it", now)
                                adapter.updateState(copy, AdjustmentStatus.PENDING)
                            }
                        }.awaitAll()

                assertThat(results.count { it }).isEqualTo(1)
            }

        @Test
        fun `항목의 두 수량과 변동량을 갱신한다`() =
            runBlocking<Unit> {
                val saved: StockAdjustment = adapter.save(proposal())
                saved.revise(100, 95)

                adapter.updateReconciliationItem(saved.items.single())

                val item: StockAdjustmentItem = checkNotNull(adapter.findById(checkNotNull(saved.stockAdjustmentId))).items.single()
                assertThat(item.wmsQuantity).isEqualTo(95)
                assertThat(item.quantityChange).isEqualTo(-5)
            }

        @Test
        fun `보정안 목록은 최신순이고 창고와 상태로 거르며 실사 조정은 제외한다`() =
            runBlocking<Unit> {
                val first: StockAdjustment = adapter.save(proposal("k-1", lotA, QualityStatus.NORMAL))
                val second: StockAdjustment = adapter.save(proposal("k-2", lotB, QualityStatus.NORMAL))
                val other: StockAdjustment = adapter.save(proposal("k-3", lotA, QualityStatus.DEFECTIVE, warehouseId = 20L))
                second.reject()
                adapter.updateState(second, AdjustmentStatus.PENDING)
                adapter.save(adjustment("audit-1"))

                fun ids(
                    warehouses: Set<Long>?,
                    status: AdjustmentStatus?,
                ) = runBlocking { adapter.searchReconciliations(warehouses, status, 0L, 100).map { it.stockAdjustmentId } }

                assertThat(ids(null, null)).containsExactly(other.stockAdjustmentId, second.stockAdjustmentId, first.stockAdjustmentId)
                assertThat(ids(setOf(10L), null)).containsExactly(second.stockAdjustmentId, first.stockAdjustmentId)
                assertThat(ids(setOf(10L, 20L), AdjustmentStatus.PENDING)).containsExactly(other.stockAdjustmentId, first.stockAdjustmentId)
                assertThat(ids(null, AdjustmentStatus.REJECTED)).containsExactly(second.stockAdjustmentId)
                assertThat(ids(emptySet(), null)).isEmpty()
                assertThat(adapter.countReconciliations(null, null)).isEqualTo(3L)
                assertThat(adapter.countReconciliations(setOf(20L), null)).isEqualTo(1L)
                assertThat(adapter.countReconciliations(null, AdjustmentStatus.APPLIED)).isEqualTo(0L)
                assertThat(adapter.countReconciliations(emptySet(), null)).isEqualTo(0L)
                assertThat(
                    adapter.searchReconciliations(null, null, 1L, 1).map { it.stockAdjustmentId },
                ).containsExactly(second.stockAdjustmentId)
            }
    }

    @Nested
    inner class `스키마` {
        private suspend fun checkValues(constraint: String): List<String> {
            val clause: String =
                databaseClient
                    .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = :name")
                    .bind("name", constraint)
                    .fetch()
                    .one()
                    .awaitFirst()["CHECK_CLAUSE"] as String
            return Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()
        }

        @Test
        fun `AdjustmentType과 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(checkValues("ck_adjustment_type")).containsExactlyInAnyOrderElementsOf(AdjustmentType.entries.map { it.name })
            }

        @Test
        fun `AdjustmentStatus와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_adjustment_status"),
                ).containsExactlyInAnyOrderElementsOf(AdjustmentStatus.entries.map { it.name })
            }

        @Test
        fun `조정 항목의 품질 상태와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_adjustment_item_quality_status"),
                ).containsExactlyInAnyOrderElementsOf(QualityStatus.entries.map { it.name })
            }
    }
}
