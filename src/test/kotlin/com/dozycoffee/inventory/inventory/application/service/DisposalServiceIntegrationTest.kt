package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmDisposalUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmDisposalCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.DisposalResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
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

/** 실제 MySQL로 폐기 확정의 수량 차감, 이력, 이벤트, 롤백, 멱등, 동시 요청을 검증한다. 서비스가 트랜잭션을 직접 연다 */
@InventoryIntegrationTest
class DisposalServiceIntegrationTest {
    @Autowired
    private lateinit var confirmDisposalUseCase: ConfirmDisposalUseCase

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

    private fun command(
        key: String = "wms-disposal-5",
        quantity: Int = 4,
        quality: QualityStatus = QualityStatus.DEFECTIVE,
        referenceId: Long = 5L,
        lotNumber: String = "LOT-A",
    ): ConfirmDisposalCommand = ConfirmDisposalCommand(10L, productId, lotNumber, quality, quantity, referenceId, key, "svc-wms")

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
        fun `불량 재고를 차감하고 DISPOSAL 이력과 감소 이벤트를 남긴다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 10)

                val result: DisposalResult = confirmDisposalUseCase.confirm(command())

                assertThat(result.inventoryId).isEqualTo(row)
                assertThat(result.quantityChange).isEqualTo(-4)
                assertThat(result.quantityAfter).isEqualTo(6)
                assertThat(quantity(row)).isEqualTo(6L)
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'DISPOSAL' AND reference_type = 'DISPOSAL_ITEM' " +
                            "AND reference_id = 5 AND quantity_change = -4 AND quantity_after = 6",
                    ),
                ).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_DECREASED'")).isEqualTo(1L)
            }

        @Test
        fun `폐기 예정 재고를 전부 폐기하면 행은 수량 0으로 남는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DISPOSAL_SCHEDULED", quantity = 4)

                val result: DisposalResult = confirmDisposalUseCase.confirm(command(quality = QualityStatus.DISPOSAL_SCHEDULED))

                assertThat(result.quantityAfter).isEqualTo(0)
                assertThat(quantity(row)).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE inventory_id = $row")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `반려` {
        @Test
        fun `정상 품질 재고는 폐기할 수 없고 수량은 그대로다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10)

                assertThrows<com.dozycoffee.inventory.global.error.InvalidDomainValueException> {
                    confirmDisposalUseCase.confirm(command(quality = QualityStatus.NORMAL))
                }

                assertThat(quantity(row)).isEqualTo(10L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `가용 수량을 넘는 폐기는 거부하고 아무것도 바뀌지 않는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 3)

                assertThrows<InsufficientAvailableQuantityException> { confirmDisposalUseCase.confirm(command(quantity = 4)) }

                assertThat(quantity(row)).isEqualTo(3L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(0L)
            }

        @Test
        fun `등록되지 않은 Lot이나 재고 행이 없으면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<LotNotFoundException> { confirmDisposalUseCase.confirm(command(lotNumber = "NOPE")) }
                assertThrows<InventoryNotFoundException> { confirmDisposalUseCase.confirm(command()) }
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 멱등 키의 재요청은 다시 차감하지 않고 처음과 같은 결과를 준다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 10)
                val first: DisposalResult = confirmDisposalUseCase.confirm(command())
                confirmDisposalUseCase.confirm(command(key = "wms-disposal-6", quantity = 1, referenceId = 6L))

                val again: DisposalResult = confirmDisposalUseCase.confirm(command())

                assertThat(again).isEqualTo(first)
                assertThat(quantity(row)).isEqualTo(5L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `같은 멱등 키에 다른 내용이면 거부한다`() =
            runBlocking<Unit> {
                fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 10)
                confirmDisposalUseCase.confirm(command())

                assertThrows<IdempotencyKeyConflictException> { confirmDisposalUseCase.confirm(command(quantity = 5)) }
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 들어와도 한 번만 차감하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 100)

                val results: List<DisposalResult> =
                    (1..20)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { confirmDisposalUseCase.confirm(command()) }
                        }.awaitAll()

                assertThat(results.toSet()).hasSize(1)
                assertThat(quantity(row)).isEqualTo(96L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(1L)
            }

        @Test
        fun `서로 다른 키의 폐기가 같은 행에 몰려도 가용 수량을 넘겨 차감하지 않는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 10)

                val results: List<Result<DisposalResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(
                                Dispatchers.IO,
                            ) { runCatching { confirmDisposalUseCase.confirm(command("disposal-$i", 1, referenceId = i.toLong())) } }
                        }.awaitAll()

                assertThat(results.count { it.isSuccess }).isEqualTo(10)
                assertThat(
                    results.filter { it.isFailure }.map { it.exceptionOrNull() },
                ).allMatch { it is InsufficientAvailableQuantityException }
                assertThat(quantity(row)).isEqualTo(0L)
            }
    }
}
