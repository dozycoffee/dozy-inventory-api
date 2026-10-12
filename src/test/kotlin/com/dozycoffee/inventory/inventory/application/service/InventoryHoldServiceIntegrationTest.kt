package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.HoldInventoryAllocationUseCase
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate

/** 실제 MySQL로 할당 보류가 저장되고, 이미 보류 중인 행은 처음의 사유를 유지하며, 예약 수량은 건드리지 않음을 검증한다 */
@InventoryIntegrationTest
class InventoryHoldServiceIntegrationTest {
    @Autowired
    private lateinit var holdInventoryAllocationUseCase: HoldInventoryAllocationUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            lotId = checkNotNull(fixture.seedLot(productId, "LOT-A", LocalDate.of(2027, 1, 1)).lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun row(inventoryId: Long): Map<String, Any?> =
        databaseClient
            .sql("SELECT allocation_hold, hold_reason, held_at, reserved_quantity FROM inventory WHERE inventory_id = $inventoryId")
            .fetch()
            .one()
            .awaitFirst()

    @Test
    fun `보류하면 사유와 시각이 저장되고 예약 수량은 그대로다`() =
        runBlocking<Unit> {
            val inventoryId: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10, reservedQuantity = 4)

            holdInventoryAllocationUseCase.hold(setOf(inventoryId), "피킹 불가 보고: 예약 1")

            val saved: Map<String, Any?> = row(inventoryId)
            assertThat(saved["allocation_hold"]).isIn(true, 1, 1L)
            assertThat(saved["hold_reason"]).isEqualTo("피킹 불가 보고: 예약 1")
            assertThat(saved["held_at"]).isNotNull()
            assertThat(saved["reserved_quantity"].toString()).isEqualTo("4")
        }

    @Test
    fun `이미 보류 중인 행은 처음의 사유를 유지한다`() =
        runBlocking<Unit> {
            val inventoryId: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10, hold = true)

            holdInventoryAllocationUseCase.hold(setOf(inventoryId), "다른 사유")

            assertThat(row(inventoryId)["hold_reason"]).isEqualTo("테스트 보류")
        }

    @Test
    fun `없는 재고 행이면 404 예외이고 잘못된 사유는 거부한다`() =
        runBlocking<Unit> {
            val inventoryId: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10)

            assertThrows<InventoryNotFoundException> { holdInventoryAllocationUseCase.hold(setOf(99_999L), "사유") }
            assertThrows<InvalidDomainValueException> { holdInventoryAllocationUseCase.hold(setOf(inventoryId), " ") }
        }
}
