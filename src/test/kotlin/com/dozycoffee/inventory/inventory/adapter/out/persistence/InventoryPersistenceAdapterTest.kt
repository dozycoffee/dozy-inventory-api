package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.inventory.application.port.out.AllocationCandidate
import com.dozycoffee.inventory.inventory.application.port.out.AvailabilityRow
import com.dozycoffee.inventory.inventory.application.port.out.InventoryLotInfo
import com.dozycoffee.inventory.inventory.domain.exception.AllocationHeldException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotReservableException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime

@DataR2dbcTest
@ActiveProfiles("local")
@Import(
    InventoryPersistenceAdapter::class,
    LotPersistenceAdapter::class,
    R2dbcConfig::class,
    ClockConfig::class,
    LocalActorProvider::class,
)
class InventoryPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: InventoryPersistenceAdapter

    @Autowired
    private lateinit var lotAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotAdapter)
            productId = fixture.seedProduct()
            lotId = checkNotNull(fixture.seedLot(productId).lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun row(
        quantity: Int = 10,
        reserved: Int = 0,
        status: String = "NORMAL",
        hold: Boolean = false,
        warehouseId: Long = 10L,
    ): Long = fixture.insertInventory(warehouseId, productId, lotId, status, quantity, reserved, hold)

    private suspend fun load(inventoryId: Long): Inventory = checkNotNull(adapter.findById(inventoryId))

    private suspend fun updatedBy(inventoryId: Long): String =
        databaseClient
            .sql("SELECT updated_by FROM inventory WHERE inventory_id = :id")
            .bind("id", inventoryId)
            .fetch()
            .one()
            .awaitFirst()["updated_by"] as String

    @Nested
    inner class `조회` {
        @Test
        fun `식별자와 키로 조회한다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 7, reserved = 2)

                val byId: Inventory = load(id)
                val byKey: Inventory? = adapter.findByKey(InventoryKey(10L, lotId, QualityStatus.NORMAL))

                assertThat(byId.quantity).isEqualTo(7)
                assertThat(byId.reservedQuantity).isEqualTo(2)
                assertThat(byId.productId).isEqualTo(productId)
                assertThat(byKey).isEqualTo(byId)
            }

        @Test
        fun `없는 행은 null이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findById(999_999L)).isNull()
                assertThat(adapter.findByKey(InventoryKey(10L, lotId, QualityStatus.DEFECTIVE))).isNull()
            }

        @Test
        fun `보류 상태와 사유와 시각을 복원한다`() =
            runBlocking<Unit> {
                val held: Inventory = load(row(hold = true))

                assertThat(held.allocationHold).isTrue()
                assertThat(held.holdReason).isEqualTo("테스트 보류")
                assertThat(held.heldAt).isEqualTo(InventoryDbFixture.HELD_AT)
            }
    }

    @Nested
    inner class `입고 upsert` {
        private val key: InventoryKey get() = InventoryKey(10L, lotId, QualityStatus.NORMAL)

        @Test
        fun `행이 없으면 수량이 입고 수량인 행을 만든다`() =
            runBlocking<Unit> {
                val created: Inventory = adapter.increase(key, productId, 5)

                assertThat(created.inventoryId).isNotNull()
                assertThat(created.quantity).isEqualTo(5)
                assertThat(created.reservedQuantity).isEqualTo(0)
                assertThat(created.allocationHold).isFalse()
            }

        @Test
        fun `행이 있으면 수량을 더하고 행은 하나로 유지한다`() =
            runBlocking<Unit> {
                val first: Inventory = adapter.increase(key, productId, 5)
                val second: Inventory = adapter.increase(key, productId, 7)

                assertThat(second.inventoryId).isEqualTo(first.inventoryId)
                assertThat(second.quantity).isEqualTo(12)
            }

        @Test
        fun `예약 수량은 입고로 바뀌지 않는다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 4)

                val after: Inventory = adapter.increase(key, productId, 3)

                assertThat(after.inventoryId).isEqualTo(id)
                assertThat(after.quantity).isEqualTo(13)
                assertThat(after.reservedQuantity).isEqualTo(4)
            }

        @Test
        fun `0이 된 행에 다시 입고하면 그 행에 더한다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 3, reserved = 3)
                adapter.ship(id, 3)

                val after: Inventory = adapter.increase(key, productId, 8)

                assertThat(after.inventoryId).isEqualTo(id)
                assertThat(after.quantity).isEqualTo(8)
            }

        @Test
        fun `품질 상태나 창고가 다르면 다른 행이다`() =
            runBlocking<Unit> {
                val normal: Inventory = adapter.increase(key, productId, 5)
                val defective: Inventory = adapter.increase(key.copy(qualityStatus = QualityStatus.DEFECTIVE), productId, 2)
                val otherWarehouse: Inventory = adapter.increase(key.copy(warehouseId = 20L), productId, 1)

                assertThat(setOf(normal.inventoryId, defective.inventoryId, otherWarehouse.inventoryId)).hasSize(3)
            }

        @Test
        fun `수량은 1 이상이어야 한다`() =
            runBlocking<Unit> {
                listOf(0, -1).forEach { amount: Int ->
                    val e: InvalidDomainValueException = assertThrows { adapter.increase(key, productId, amount) }
                    assertThat(e.errorCode).isEqualTo(InventoryErrorCode.INVALID_QUANTITY)
                }
            }

        @Test
        fun `Lot의 상품과 다른 상품으로는 입고할 수 없다`() =
            runBlocking<Unit> {
                val otherProduct: Long = fixture.seedProduct("BEAN-002")

                assertThrows<DataIntegrityViolationException> { adapter.increase(key, otherProduct, 1) }
            }

        @Test
        fun `수정자는 현재 Actor로 기록한다`() =
            runBlocking<Unit> {
                val inventory: Inventory = adapter.increase(key, productId, 5)

                assertThat(updatedBy(checkNotNull(inventory.inventoryId))).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            }
    }

    @Nested
    inner class `예약` {
        @Test
        fun `가용 수량 안에서 예약하고 정확히 가용 수량만큼도 예약한다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 3)

                val after: Inventory = adapter.reserve(id, 7)

                assertThat(after.reservedQuantity).isEqualTo(10)
                assertThat(after.quantity).isEqualTo(10)
                assertThat(after.availableQuantity).isEqualTo(0)
            }

        @Test
        fun `가용 수량을 넘으면 재고 부족이고 값은 바뀌지 않는다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 3)

                assertThrows<InsufficientAvailableQuantityException> { adapter.reserve(id, 8) }

                assertThat(load(id).reservedQuantity).isEqualTo(3)
            }

        @Test
        fun `정상 품질이 아니면 예약할 수 없다`() =
            runBlocking<Unit> {
                assertThrows<InventoryNotReservableException> { adapter.reserve(row(status = "DEFECTIVE"), 1) }
                assertThrows<InventoryNotReservableException> { adapter.reserve(row(status = "DISPOSAL_SCHEDULED", warehouseId = 11L), 1) }
            }

        @Test
        fun `할당 보류 행은 예약할 수 없다`() =
            runBlocking<Unit> {
                assertThrows<AllocationHeldException> { adapter.reserve(row(hold = true), 1) }
            }

        @Test
        fun `없는 행은 NotFound이고 수량은 1 이상이어야 한다`() =
            runBlocking<Unit> {
                assertThrows<InventoryNotFoundException> { adapter.reserve(999_999L, 1) }
                assertThrows<InvalidDomainValueException> { adapter.reserve(row(), 0) }
            }

        @Test
        fun `수정자는 현재 Actor로 기록한다`() =
            runBlocking<Unit> {
                val id: Long = row()

                adapter.reserve(id, 1)

                assertThat(updatedBy(id)).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            }
    }

    @Nested
    inner class `예약 해제` {
        @Test
        fun `예약 수량만 줄고 품질 상태와 보류와 무관하다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 6, status = "DEFECTIVE", hold = true)

                val after: Inventory = adapter.release(id, 4)

                assertThat(after.reservedQuantity).isEqualTo(2)
                assertThat(after.quantity).isEqualTo(10)
            }

        @Test
        fun `예약 수량보다 많이 해제하면 예약 부족이다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 3)

                assertThrows<InsufficientReservedQuantityException> { adapter.release(id, 4) }

                assertThat(load(id).reservedQuantity).isEqualTo(3)
            }

        @Test
        fun `없는 행은 NotFound`() =
            runBlocking<Unit> {
                assertThrows<InventoryNotFoundException> { adapter.release(999_999L, 1) }
            }
    }

    @Nested
    inner class `출고 확정` {
        @Test
        fun `총 수량과 예약 수량을 함께 줄인다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 5)

                val after: Inventory = adapter.ship(id, 5)

                assertThat(after.quantity).isEqualTo(5)
                assertThat(after.reservedQuantity).isEqualTo(0)
            }

        @Test
        fun `0이 되어도 행은 삭제되지 않는다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 4, reserved = 4)

                val after: Inventory = adapter.ship(id, 4)

                assertThat(after.quantity).isEqualTo(0)
                assertThat(adapter.findById(id)).isNotNull()
            }

        @Test
        fun `예약 수량보다 많이 출고하면 예약 부족이다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 3)

                assertThrows<InsufficientReservedQuantityException> { adapter.ship(id, 4) }

                assertThat(load(id).quantity).isEqualTo(10)
            }
    }

    @Nested
    inner class `감소` {
        @Test
        fun `가용 수량 안에서 총 수량을 줄인다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 4)

                val after: Inventory = adapter.decrease(id, 6)

                assertThat(after.quantity).isEqualTo(4)
                assertThat(after.reservedQuantity).isEqualTo(4)
            }

        @Test
        fun `예약된 수량까지 줄이려 하면 재고 부족이다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10, reserved = 4)

                assertThrows<InsufficientAvailableQuantityException> { adapter.decrease(id, 7) }

                assertThat(load(id).quantity).isEqualTo(10)
            }
    }

    @Nested
    inner class `할당 보류` {
        @Test
        fun `보류하면 사유와 시각을 기록한다`() =
            runBlocking<Unit> {
                val id: Long = row()
                val at: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)

                val held: Inventory = adapter.holdAllocation(id, "Lot 부족 보고", at)

                assertThat(held.allocationHold).isTrue()
                assertThat(held.holdReason).isEqualTo("Lot 부족 보고")
                assertThat(held.heldAt).isEqualTo(at)
            }

        @Test
        fun `이미 보류 중이면 처음의 사유와 시각을 유지한다`() =
            runBlocking<Unit> {
                val id: Long = row(hold = true)

                val held: Inventory = adapter.holdAllocation(id, "다른 사유", LocalDateTime.of(2026, 10, 8, 0, 0))

                assertThat(held.holdReason).isEqualTo("테스트 보류")
                assertThat(held.heldAt).isEqualTo(InventoryDbFixture.HELD_AT)
            }

        @Test
        fun `보류를 풀면 사유와 시각이 지워진다`() =
            runBlocking<Unit> {
                val id: Long = row(hold = true)

                val released: Inventory = adapter.releaseAllocationHold(id)

                assertThat(released.allocationHold).isFalse()
                assertThat(released.holdReason).isNull()
                assertThat(released.heldAt).isNull()
                assertThat(adapter.releaseAllocationHold(id).allocationHold).isFalse()
            }

        @Test
        fun `사유가 잘못되면 실패하고 없는 행은 NotFound`() =
            runBlocking<Unit> {
                val id: Long = row()

                assertThrows<InvalidDomainValueException> { adapter.holdAllocation(id, " ", LocalDateTime.now()) }
                assertThrows<InventoryNotFoundException> { adapter.holdAllocation(999_999L, "사유", LocalDateTime.now()) }
                assertThrows<InventoryNotFoundException> { adapter.releaseAllocationHold(999_999L) }
            }
    }

    @Nested
    inner class `SQL과 도메인 모델 규칙 일치` {
        private val states: List<State> =
            listOf("NORMAL", "DEFECTIVE", "DISPOSAL_SCHEDULED").flatMap { status: String ->
                listOf(false, true).flatMap { hold: Boolean ->
                    listOf(0 to 0, 5 to 0, 5 to 3, 5 to 5, 1 to 1).map { (quantity: Int, reserved: Int) ->
                        State(quantity, reserved, status, hold)
                    }
                }
            }

        private val amounts: List<Int> = listOf(1, 2, 3, 5, 6)

        private fun model(state: State): Inventory =
            Inventory.reconstitute(
                1L,
                10L,
                1L,
                1L,
                QualityStatus.valueOf(state.status),
                state.quantity,
                state.reservedQuantity(),
                state.hold,
                if (state.hold) "x" else null,
                if (state.hold) InventoryDbFixture.HELD_AT else null,
            )

        private fun State.reservedQuantity(): Int = reserved

        private suspend fun outcome(block: suspend () -> Inventory): String =
            try {
                val after: Inventory = block()
                "OK(${after.quantity},${after.reservedQuantity})"
            } catch (e: Exception) {
                e::class.simpleName.orEmpty()
            }

        private fun modelOutcome(
            state: State,
            operation: (Inventory) -> Unit,
        ): String =
            try {
                val inventory: Inventory = model(state)
                operation(inventory)
                "OK(${inventory.quantity},${inventory.reservedQuantity})"
            } catch (e: Exception) {
                e::class.simpleName.orEmpty()
            }

        private suspend fun assertSame(
            name: String,
            sqlOperation: suspend (Long, Int) -> Inventory,
            modelOperation: (Inventory, Int) -> Unit,
        ) {
            val mismatches: MutableList<String> = mutableListOf()
            states.forEachIndexed { index: Int, state: State ->
                amounts.forEachIndexed { amountIndex: Int, amount: Int ->
                    val id: Long =
                        row(
                            state.quantity,
                            state.reserved,
                            state.status,
                            state.hold,
                            warehouseId =
                                1000L + index * 10 + amountIndex,
                        )
                    val actual: String = outcome { sqlOperation(id, amount) }
                    val expected: String = modelOutcome(state) { modelOperation(it, amount) }
                    if (actual != expected) mismatches.add("$name $state amount=$amount: SQL=$actual 모델=$expected")
                }
            }
            assertThat(mismatches).describedAs("SQL 조건과 도메인 모델 규칙이 달라지는 경우").isEmpty()
        }

        @Test
        fun `예약`() = runBlocking<Unit> { assertSame("reserve", adapter::reserve) { inventory, amount -> inventory.reserve(amount) } }

        @Test
        fun `감소`() = runBlocking<Unit> { assertSame("decrease", adapter::decrease) { inventory, amount -> inventory.decrease(amount) } }

        @Test
        fun `예약 해제`() = runBlocking<Unit> { assertSame("release", adapter::release) { inventory, amount -> inventory.release(amount) } }

        @Test
        fun `출고 확정`() = runBlocking<Unit> { assertSame("ship", adapter::ship) { inventory, amount -> inventory.ship(amount) } }
    }

    @Nested
    inner class `가용 재고 조회` {
        private var otherProductId: Long = 0L
        private var otherLotId: Long = 0L

        @BeforeEach
        fun seedOtherProduct() =
            runBlocking<Unit> {
                otherProductId = fixture.seedProduct("BEAN-002")
                otherLotId = checkNotNull(fixture.seedLot(otherProductId, "LOT-B").lotId)
            }

        private suspend fun availability(
            vararg products: Long,
            warehouses: Set<Long>? = null,
        ): List<AvailabilityRow> = adapter.findAvailability(products.toSet(), warehouses)

        @Test
        fun `정상 품질이고 보류가 아닌 행의 총 수량에서 예약을 뺀 값을 창고별로 합산한다`() =
            runBlocking<Unit> {
                row(quantity = 10, reserved = 3, warehouseId = 10L)
                fixture.insertInventory(10L, productId, checkNotNull(fixture.seedLot(productId, "LOT-C").lotId), "NORMAL", 5, 0, false)
                row(quantity = 7, reserved = 0, warehouseId = 20L)

                val rows: List<AvailabilityRow> = availability(productId)

                assertThat(rows).containsExactly(AvailabilityRow(10L, productId, 12L), AvailabilityRow(20L, productId, 7L))
            }

        @Test
        fun `불량·폐기 예정과 보류 행은 가용에서 빼지만 재고 행이 있는 창고는 0으로 나온다`() =
            runBlocking<Unit> {
                row(quantity = 10, status = "DEFECTIVE", warehouseId = 10L)
                row(quantity = 10, status = "DISPOSAL_SCHEDULED", warehouseId = 11L)
                row(quantity = 10, hold = true, warehouseId = 12L)

                val rows: List<AvailabilityRow> = availability(productId)

                assertThat(rows).containsExactly(
                    AvailabilityRow(10L, productId, 0L),
                    AvailabilityRow(11L, productId, 0L),
                    AvailabilityRow(12L, productId, 0L),
                )
            }

        @Test
        fun `같은 창고에서 정상 행과 불량 행이 섞여도 정상 행만 센다`() =
            runBlocking<Unit> {
                row(quantity = 10, reserved = 2, warehouseId = 10L)
                row(quantity = 99, status = "DEFECTIVE", warehouseId = 10L)

                assertThat(availability(productId)).containsExactly(AvailabilityRow(10L, productId, 8L))
            }

        @Test
        fun `창고를 지정하면 그 창고만 센다`() =
            runBlocking<Unit> {
                row(quantity = 10, warehouseId = 10L)
                row(quantity = 7, warehouseId = 20L)
                row(quantity = 3, warehouseId = 30L)

                val rows: List<AvailabilityRow> = availability(productId, warehouses = setOf(20L, 30L, 99L))

                assertThat(rows).containsExactly(AvailabilityRow(20L, productId, 7L), AvailabilityRow(30L, productId, 3L))
            }

        @Test
        fun `상품을 여러 개 지정하면 지정한 상품만 상품 ID와 창고 ID 순서로 센다`() =
            runBlocking<Unit> {
                row(quantity = 10, warehouseId = 20L)
                row(quantity = 4, warehouseId = 10L)
                fixture.insertInventory(10L, otherProductId, otherLotId, "NORMAL", 6, 1, false)
                val third: Long = fixture.seedProduct("BEAN-003")
                fixture.insertInventory(10L, third, checkNotNull(fixture.seedLot(third, "LOT-D").lotId), "NORMAL", 100, 0, false)

                val rows: List<AvailabilityRow> = availability(productId, otherProductId)

                assertThat(rows).containsExactly(
                    AvailabilityRow(10L, productId, 4L),
                    AvailabilityRow(20L, productId, 10L),
                    AvailabilityRow(10L, otherProductId, 5L),
                )
            }

        @Test
        fun `재고 행이 없는 상품이나 빈 조건은 결과가 없다`() =
            runBlocking<Unit> {
                row(quantity = 10)

                assertThat(availability(otherProductId)).isEmpty()
                assertThat(adapter.findAvailability(emptySet(), null)).isEmpty()
                assertThat(adapter.findAvailability(setOf(productId), emptySet())).isEmpty()
            }

        @Test
        fun `수량 0인 행은 0으로 나온다`() =
            runBlocking<Unit> {
                row(quantity = 0, warehouseId = 10L)

                assertThat(availability(productId)).containsExactly(AvailabilityRow(10L, productId, 0L))
            }

        @Test
        fun `Int 범위를 넘는 합계도 정확하다`() =
            runBlocking<Unit> {
                row(quantity = Int.MAX_VALUE, warehouseId = 10L)
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "LOT-E").lotId),
                    "NORMAL",
                    Int.MAX_VALUE,
                    0,
                    false,
                )

                assertThat(availability(productId)).containsExactly(AvailabilityRow(10L, productId, Int.MAX_VALUE.toLong() * 2))
            }

        @Test
        fun `SQL의 가용 계산이 도메인 모델의 할당 가능 규칙과 같다`() =
            runBlocking<Unit> {
                val mismatches: MutableList<String> = mutableListOf()
                var warehouse = 2000L
                listOf("NORMAL", "DEFECTIVE", "DISPOSAL_SCHEDULED").forEach { status: String ->
                    listOf(false, true).forEach { hold: Boolean ->
                        listOf(0 to 0, 5 to 0, 5 to 3, 5 to 5).forEach { (quantity: Int, reserved: Int) ->
                            warehouse++
                            val model: Inventory =
                                Inventory.reconstitute(
                                    1L,
                                    warehouse,
                                    productId,
                                    lotId,
                                    QualityStatus.valueOf(status),
                                    quantity,
                                    reserved,
                                    hold,
                                    if (hold) "x" else null,
                                    if (hold) InventoryDbFixture.HELD_AT else null,
                                )
                            val expected: Long = if (model.isAllocatable) model.availableQuantity.toLong() else 0L
                            row(quantity, reserved, status, hold, warehouseId = warehouse)

                            val actual: Long = availability(productId, warehouses = setOf(warehouse)).single().availableQuantity
                            if (actual !=
                                expected
                            ) {
                                mismatches.add("$status hold=$hold qty=$quantity reserved=$reserved: SQL=$actual 모델=$expected")
                            }
                        }
                    }
                }

                assertThat(mismatches).describedAs("SQL의 가용 계산과 도메인 모델이 달라지는 경우").isEmpty()
            }
    }

    @Nested
    inner class `예약 후보 조회` {
        private val today: LocalDate = LocalDate.of(2026, 10, 7)

        private suspend fun lot(
            number: String,
            expiration: LocalDate?,
        ): Long = checkNotNull(fixture.seedLot(productId, number, expiration).lotId)

        @Test
        fun `유통기한이 이른 순서이고 유통기한이 없는 Lot은 마지막이며 같으면 행 ID 순서다`() =
            runBlocking<Unit> {
                val none: Long = fixture.insertInventory(10L, productId, lot("NONE", null))
                val late: Long = fixture.insertInventory(10L, productId, lot("LATE", LocalDate.of(2027, 6, 1)))
                val early: Long = fixture.insertInventory(10L, productId, lot("EARLY", LocalDate.of(2027, 1, 1)))
                val sameLot: Long = lot("SAME", LocalDate.of(2027, 1, 1))
                val sameFirst: Long = fixture.insertInventory(10L, productId, sameLot, qualityStatus = "NORMAL")

                val candidates: List<AllocationCandidate> = adapter.findAllocationCandidates(10L, setOf(productId), today)

                assertThat(candidates.map { it.inventoryId }).containsExactly(early, sameFirst, late, none)
                assertThat(candidates.first().availableQuantity).isEqualTo(10)
                assertThat(candidates.first().expirationDate).isEqualTo(LocalDate.of(2027, 1, 1))
            }

        @Test
        fun `가용 수량은 총 수량에서 예약 수량을 뺀 값이고 남은 수량이 없으면 제외한다`() =
            runBlocking<Unit> {
                val partly: Long =
                    fixture.insertInventory(
                        10L,
                        productId,
                        lot("A", LocalDate.of(2027, 1, 1)),
                        quantity = 10,
                        reservedQuantity = 4,
                    )
                fixture.insertInventory(10L, productId, lot("B", LocalDate.of(2027, 2, 1)), quantity = 10, reservedQuantity = 10)

                val candidates: List<AllocationCandidate> = adapter.findAllocationCandidates(10L, setOf(productId), today)

                assertThat(candidates.map { it.inventoryId }).containsExactly(partly)
                assertThat(candidates.single().availableQuantity).isEqualTo(6)
            }

        @Test
        fun `불량 품질, 할당 보류, 다른 창고, 유통기한이 지났거나 오늘인 Lot은 제외한다`() =
            runBlocking<Unit> {
                val ok: Long = fixture.insertInventory(10L, productId, lot("OK", LocalDate.of(2026, 10, 8)))
                fixture.insertInventory(10L, productId, lot("DEFECT", LocalDate.of(2027, 1, 1)), qualityStatus = "DEFECTIVE")
                fixture.insertInventory(10L, productId, lot("HELD", LocalDate.of(2027, 1, 1)), hold = true)
                fixture.insertInventory(20L, productId, lot("OTHER", LocalDate.of(2027, 1, 1)))
                fixture.insertInventory(10L, productId, lot("TODAY", today))
                fixture.insertInventory(10L, productId, lot("OLD", LocalDate.of(2026, 10, 1)))

                val candidates: List<AllocationCandidate> = adapter.findAllocationCandidates(10L, setOf(productId), today)

                assertThat(candidates.map { it.inventoryId }).containsExactly(ok)
            }

        @Test
        fun `여러 상품을 한 번에 조회하고 상품별로 묶어 반환한다`() =
            runBlocking<Unit> {
                val other: Long = fixture.seedProduct("BEAN-002")
                val otherLot: Long = checkNotNull(fixture.seedLot(other, "X", LocalDate.of(2027, 1, 1)).lotId)
                val a: Long = fixture.insertInventory(10L, productId, lot("A", LocalDate.of(2027, 1, 1)))
                val b: Long = fixture.insertInventory(10L, other, otherLot)

                val candidates: List<AllocationCandidate> = adapter.findAllocationCandidates(10L, setOf(productId, other), today)

                assertThat(candidates.map { it.productId to it.inventoryId }).containsExactlyInAnyOrder(productId to a, other to b)
                assertThat(adapter.findAllocationCandidates(10L, emptySet(), today)).isEmpty()
            }
    }

    @Nested
    inner class `Lot 정보 조회` {
        @Test
        fun `재고 행의 Lot ID와 번호와 유통기한을 재고 행 ID 순서로 조회한다`() =
            runBlocking<Unit> {
                val dated: Lot = fixture.seedLot(productId, "LOT-X", LocalDate.of(2027, 1, 1))
                val undated: Lot = fixture.seedLot(productId, "LOT-Y", null)
                val first: Long = fixture.insertInventory(10L, productId, checkNotNull(dated.lotId))
                val second: Long = fixture.insertInventory(10L, productId, checkNotNull(undated.lotId))

                val infos: List<InventoryLotInfo> = adapter.findLotInfos(setOf(second, first))

                assertThat(infos).containsExactly(
                    InventoryLotInfo(first, checkNotNull(dated.lotId), "LOT-X", LocalDate.of(2027, 1, 1)),
                    InventoryLotInfo(second, checkNotNull(undated.lotId), "LOT-Y", null),
                )
            }

        @Test
        fun `없는 재고 행은 결과에 없고 빈 집합은 빈 목록이다`() =
            runBlocking<Unit> {
                val inventoryId: Long = fixture.insertInventory(10L, productId, lotId)

                assertThat(adapter.findLotInfos(setOf(inventoryId, inventoryId + 1000)).map { it.inventoryId }).containsExactly(inventoryId)
                assertThat(adapter.findLotInfos(emptySet())).isEmpty()
            }
    }

    @Nested
    inner class `스키마` {
        @Test
        fun `QualityStatus와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                val clause: String =
                    databaseClient
                        .sql(
                            "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'ck_inventory_quality_status'",
                        ).fetch()
                        .one()
                        .awaitFirst()["CHECK_CLAUSE"] as String
                val values: List<String> = Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()

                assertThat(values).containsExactlyInAnyOrderElementsOf(QualityStatus.entries.map { it.name })
            }

        @Test
        fun `모든 품질 상태로 재고 행을 저장할 수 있다`() =
            runBlocking<Unit> {
                QualityStatus.entries.forEach { status: QualityStatus ->
                    adapter.increase(InventoryKey(10L, lotId, status), productId, 1)
                }

                assertThat(QualityStatus.entries.map { adapter.findByKey(InventoryKey(10L, lotId, it)) }).doesNotContainNull()
            }
    }

    @Nested
    inner class `동시성` {
        @Test
        fun `가용 10에 50개가 동시에 1씩 예약하면 정확히 10개만 성공한다`() =
            runBlocking<Unit> {
                val id: Long = row(quantity = 10)

                val failures: List<Throwable?> =
                    (1..50).map { async(Dispatchers.Default) { runCatching { adapter.reserve(id, 1) }.exceptionOrNull() } }.awaitAll()

                assertThat(failures.count { it == null }).isEqualTo(10)
                assertThat(failures.filterNotNull()).allMatch { it is InsufficientAvailableQuantityException }
                val after: Inventory = load(id)
                assertThat(after.reservedQuantity).isEqualTo(10)
                assertThat(after.quantity).isEqualTo(10)
            }

        @Test
        fun `같은 키에 50개가 동시에 입고해도 행은 하나이고 합계가 정확하다`() =
            runBlocking<Unit> {
                val key: InventoryKey = InventoryKey(10L, lotId, QualityStatus.NORMAL)

                val failures: List<Throwable?> =
                    (1..50)
                        .map {
                            async(
                                Dispatchers.Default,
                            ) { runCatching { adapter.increase(key, productId, 2) }.exceptionOrNull() }
                        }.awaitAll()

                assertThat(failures.filterNotNull()).describedAs("동시 입고 실패").isEmpty()
                assertThat(load(checkNotNull(adapter.findByKey(key)?.inventoryId)).quantity).isEqualTo(100)
                val rows: Long =
                    databaseClient
                        .sql("SELECT COUNT(*) AS c FROM inventory")
                        .fetch()
                        .one()
                        .awaitFirst()["c"]
                        .toString()
                        .toLong()
                assertThat(rows).isEqualTo(1L)
            }
    }
}

private data class State(
    val quantity: Int,
    val reserved: Int,
    val status: String,
    val hold: Boolean,
)
