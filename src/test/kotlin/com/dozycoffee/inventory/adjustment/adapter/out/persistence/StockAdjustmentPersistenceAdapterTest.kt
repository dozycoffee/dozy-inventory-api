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
