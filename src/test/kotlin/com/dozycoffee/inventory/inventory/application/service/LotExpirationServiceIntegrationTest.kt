package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.FindLotsToScanUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ProcessLotExpirationUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotExpirationResult
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.LocalDate

/** 실제 MySQL로 유통기한 스캔의 상태 갱신, 품질 전환, 이력, 멱등, 동시 처리를 검증한다. 서비스가 트랜잭션을 직접 연다 */
@InventoryIntegrationTest
class LotExpirationServiceIntegrationTest {
    @Autowired
    private lateinit var findLotsToScanUseCase: FindLotsToScanUseCase

    @Autowired
    private lateinit var processLotExpirationUseCase: ProcessLotExpirationUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private lateinit var today: LocalDate

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            today = LocalDate.now(clock)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun lot(
        number: String,
        expiration: LocalDate?,
        status: String,
    ): Long {
        val lotId: Long = checkNotNull(fixture.seedLot(productId, number, expiration).lotId)
        databaseClient
            .sql("UPDATE lot SET lot_status = :status WHERE lot_id = :id")
            .bind("status", status)
            .bind("id", lotId)
            .fetch()
            .rowsUpdated()
            .awaitFirst()
        return lotId
    }

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

    private suspend fun quantity(
        lotId: Long,
        warehouseId: Long,
        quality: String,
    ): Long =
        scalar(
            "SELECT COALESCE(SUM(quantity), -1) FROM inventory WHERE lot_id = $lotId AND warehouse_id = $warehouseId AND quality_status = '$quality'",
        )

    @Nested
    inner class `상태 갱신` {
        @Test
        fun `임박 기준에 들어온 Lot은 상태만 바뀌고 재고는 그대로다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("SOON", today.plusDays(10), "NORMAL")
                fixture.insertInventory(10L, productId, lotId, quantity = 10)

                val result: LotExpirationResult = processLotExpirationUseCase.process(lotId)

                assertThat(result.statusChanged).isTrue()
                assertThat(result.expired).isFalse()
                assertThat(result.transfers).isEmpty()
                assertThat(scalar("SELECT COUNT(*) FROM lot WHERE lot_id = $lotId AND lot_status = 'EXPIRING_SOON'")).isEqualTo(1L)
                assertThat(quantity(lotId, 10L, "NORMAL")).isEqualTo(10L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `후보 조회는 상태가 바뀔 Lot과 가용 수량이 남은 경과 Lot을 준다`() =
            runBlocking<Unit> {
                val soon: Long = lot("SOON", today.plusDays(30), "NORMAL")
                val expiredWithStock: Long = lot("EXP", today.minusDays(1), "EXPIRED")
                lot("FAR", today.plusDays(90), "NORMAL")
                fixture.insertInventory(10L, productId, expiredWithStock, quantity = 5)

                assertThat(findLotsToScanUseCase.findLotIds(100)).containsExactly(soon, expiredWithStock)
            }
    }

    @Nested
    inner class `품질 전환` {
        @Test
        fun `경과로 바뀐 Lot은 모든 창고의 정상 품질 가용 수량을 폐기 예정 행으로 옮기고 이력을 남긴다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("EXP", today.minusDays(1), "EXPIRING_SOON")
                val reserved: Long = fixture.insertInventory(10L, productId, lotId, quantity = 100, reservedQuantity = 30)
                fixture.insertInventory(20L, productId, lotId, quantity = 40)
                fixture.insertInventory(20L, productId, lotId, "DEFECTIVE", quantity = 9)

                val result: LotExpirationResult = processLotExpirationUseCase.process(lotId)

                assertThat(result.becameExpired).isTrue()
                assertThat(result.transfers.map { it.warehouseId to it.quantity }).containsExactly(10L to 70, 20L to 40)
                assertThat(result.transfers.first().remainingReservedQuantity).isEqualTo(30)
                assertThat(quantity(lotId, 10L, "NORMAL")).isEqualTo(30L)
                assertThat(quantity(lotId, 10L, "DISPOSAL_SCHEDULED")).isEqualTo(70L)
                assertThat(quantity(lotId, 20L, "NORMAL")).isEqualTo(0L)
                assertThat(quantity(lotId, 20L, "DISPOSAL_SCHEDULED")).isEqualTo(40L)
                assertThat(quantity(lotId, 20L, "DEFECTIVE")).isEqualTo(9L)
                assertThat(scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $reserved")).isEqualTo(30L)
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'QUALITY_TRANSFER' AND reference_type = 'LOT_EXPIRATION' AND reference_id = $lotId",
                    ),
                ).isEqualTo(4L)
                assertThat(scalar("SELECT SUM(quantity_change) FROM inventory_history")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(0L)
            }

        @Test
        fun `이미 폐기 예정 행이 있으면 그 행에 더한다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("EXP", today.minusDays(1), "EXPIRED")
                fixture.insertInventory(10L, productId, lotId, quantity = 20)
                val target: Long = fixture.insertInventory(10L, productId, lotId, "DISPOSAL_SCHEDULED", quantity = 5)

                processLotExpirationUseCase.process(lotId)

                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $target")).isEqualTo(25L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE lot_id = $lotId")).isEqualTo(2L)
            }

        @Test
        fun `같은 날 다시 처리하면 변화 없는 결과를 주고 이력과 수량은 그대로다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("EXP", today.minusDays(1), "EXPIRING_SOON")
                fixture.insertInventory(10L, productId, lotId, quantity = 20, reservedQuantity = 5)
                processLotExpirationUseCase.process(lotId)
                databaseClient
                    .sql("UPDATE inventory SET reserved_quantity = 0 WHERE quality_status = 'NORMAL'")
                    .fetch()
                    .rowsUpdated()
                    .awaitFirst()

                val again: LotExpirationResult = processLotExpirationUseCase.process(lotId)

                assertThat(again.transfers).isEmpty()
                assertThat(again.statusChanged).isFalse()
                assertThat(quantity(lotId, 10L, "DISPOSAL_SCHEDULED")).isEqualTo(15L)
                assertThat(quantity(lotId, 10L, "NORMAL")).isEqualTo(5L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `같은 Lot을 동시에 처리해도 한 번만 옮기고 총 수량은 변하지 않는다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("EXP", today.minusDays(1), "EXPIRING_SOON")
                fixture.insertInventory(10L, productId, lotId, quantity = 60, reservedQuantity = 10)
                fixture.insertInventory(20L, productId, lotId, quantity = 40)

                val results: List<LotExpirationResult> =
                    (1..10).map { async(Dispatchers.IO) { processLotExpirationUseCase.process(lotId) } }.awaitAll()

                assertThat(results.sumOf { it.transfers.sumOf { transfer -> transfer.quantity } }).isEqualTo(90)
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo(100L)
                assertThat(quantity(lotId, 10L, "DISPOSAL_SCHEDULED")).isEqualTo(50L)
                assertThat(quantity(lotId, 20L, "DISPOSAL_SCHEDULED")).isEqualTo(40L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(4L)
            }

        @Test
        fun `예약으로 가용 수량이 없는 행은 옮기지 않고 이력도 남기지 않는다`() =
            runBlocking<Unit> {
                val lotId: Long = lot("EXP", today.minusDays(1), "EXPIRED")
                val row: Long = fixture.insertInventory(10L, productId, lotId, quantity = 50, reservedQuantity = 50)

                val result: LotExpirationResult = processLotExpirationUseCase.process(lotId)

                assertThat(result.transfers).isEmpty()
                assertThat(scalar("SELECT quantity FROM inventory WHERE inventory_id = $row")).isEqualTo(50L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }
    }
}
