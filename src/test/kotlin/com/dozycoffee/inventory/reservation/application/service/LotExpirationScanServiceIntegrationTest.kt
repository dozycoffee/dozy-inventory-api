package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ScanLotExpirationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 실제 MySQL로 유통기한 스캔 전체(예약 강제 해제, 확정 예약 건너뜀, 품질 전환, 이벤트)를 끝까지 검증한다 */
@InventoryIntegrationTest
class LotExpirationScanServiceIntegrationTest {
    @Autowired
    private lateinit var scanLotExpirationUseCase: ScanLotExpirationUseCase

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var releaseReservationUseCase: ReleaseReservationUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotId: Long = 0L
    private var normalRow: Long = 0L
    private lateinit var today: LocalDate

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            today = LocalDate.now(clock)
            productId = fixture.seedProduct()
            // 예약을 잡을 때는 아직 경과 전이어야 하므로 유통기한을 내일로 만들고, 예약 뒤에 어제로 바꾼다
            lotId = checkNotNull(fixture.seedLot(productId, "EXP", today.plusDays(1)).lotId)
            normalRow = fixture.insertInventory(10L, productId, lotId, quantity = 100)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun reserve(
        quantity: Int,
        orderId: String,
    ): ReservationResult =
        createReservationUseCase.create(
            CreateReservationCommand(
                10L,
                "OMS",
                orderId,
                listOf(CreateReservationCommand.Item(productId, quantity)),
                LocalDateTime.now(clock).plusMinutes(30),
                "key-$orderId",
                "svc-oms",
            ),
        )

    private suspend fun expireLot() {
        databaseClient
            .sql("UPDATE lot SET expiration_date = :date, lot_status = 'EXPIRING_SOON' WHERE lot_id = :id")
            .bind("date", today.minusDays(1))
            .bind("id", lotId)
            .fetch()
            .rowsUpdated()
            .awaitFirst()
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

    private suspend fun quantity(quality: String): Long =
        scalar("SELECT COALESCE(SUM(quantity), 0) FROM inventory WHERE lot_id = $lotId AND quality_status = '$quality'")

    private suspend fun status(reservationId: Long): Long =
        scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = $reservationId AND status = 'RELEASED'")

    @Test
    fun `확정 전 예약은 강제 해제해 같은 스캔에서 옮기고 확정 예약은 건너뛰며 이벤트로 알린다`() =
        runBlocking<Unit> {
            val unconfirmed: ReservationResult = reserve(30, "ORDER-A")
            val confirmed: ReservationResult = reserve(20, "ORDER-B")
            confirmReservationUseCase.confirm(confirmed.reservationId)
            expireLot()

            val result: LotExpirationScanResult = scanLotExpirationUseCase.scan(100)

            assertThat(result).isEqualTo(LotExpirationScanResult(1, 1, 1))
            assertThat(status(unconfirmed.reservationId)).isEqualTo(1L)
            assertThat(
                scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = ${confirmed.reservationId} AND status = 'CONFIRMED'"),
            ).isEqualTo(1L)
            assertThat(quantity("NORMAL")).isEqualTo(20L)
            assertThat(scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $normalRow")).isEqualTo(20L)
            assertThat(quantity("DISPOSAL_SCHEDULED")).isEqualTo(80L)
            assertThat(scalar("SELECT COUNT(*) FROM lot WHERE lot_id = $lotId AND lot_status = 'EXPIRED'")).isEqualTo(1L)
            assertThat(
                scalar(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RESERVATION_FORCE_RELEASED' AND payload->>'$.data.reason' = 'LOT_EXPIRED'",
                ),
            ).isEqualTo(1L)
            assertThat(
                scalar("SELECT payload->>'$.data.transfers[0].quantity' FROM outbox_event WHERE event_type = 'LOT_EXPIRED'"),
            ).isEqualTo(80L)
            assertThat(scalar("SELECT payload->>'$.data.confirmedReservationIds[0]' FROM outbox_event WHERE event_type = 'LOT_EXPIRED'"))
                .isEqualTo(confirmed.reservationId)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type LIKE 'INVENTORY_%'")).isEqualTo(0L)
        }

    @Test
    fun `다시 스캔하면 처리할 것이 없고 이벤트도 더 생기지 않는다`() =
        runBlocking<Unit> {
            reserve(30, "ORDER-A")
            val confirmed: ReservationResult = reserve(20, "ORDER-B")
            confirmReservationUseCase.confirm(confirmed.reservationId)
            expireLot()
            scanLotExpirationUseCase.scan(100)
            val events: Long = scalar("SELECT COUNT(*) FROM outbox_event")

            val again: LotExpirationScanResult = scanLotExpirationUseCase.scan(100)

            assertThat(again).isEqualTo(LotExpirationScanResult(0, 0, 0))
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(events)
        }

    @Test
    fun `확정 예약이 같은 날 풀려도 이미 처리한 Lot은 다음 날 스캔에서 이어서 옮긴다`() =
        runBlocking<Unit> {
            val confirmed: ReservationResult = reserve(20, "ORDER-B")
            confirmReservationUseCase.confirm(confirmed.reservationId)
            expireLot()
            scanLotExpirationUseCase.scan(100)
            releaseReservationUseCase.release(confirmed.reservationId)

            val sameDay: LotExpirationScanResult = scanLotExpirationUseCase.scan(100)

            assertThat(sameDay.attempted).isEqualTo(1)
            assertThat(quantity("NORMAL")).isEqualTo(20L)
            assertThat(quantity("DISPOSAL_SCHEDULED")).isEqualTo(80L)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'LOT_EXPIRED'")).isEqualTo(1L)
        }

    @Test
    fun `스캔이 여러 개 동시에 돌아도 Lot은 한 번만 처리하고 총 수량은 변하지 않는다`() =
        runBlocking<Unit> {
            reserve(30, "ORDER-A")
            val confirmed: ReservationResult = reserve(20, "ORDER-B")
            confirmReservationUseCase.confirm(confirmed.reservationId)
            expireLot()

            (1..6).map { async(Dispatchers.IO) { scanLotExpirationUseCase.scan(100) } }.awaitAll()

            assertThat(quantity("NORMAL") + quantity("DISPOSAL_SCHEDULED")).isEqualTo(100L)
            assertThat(quantity("DISPOSAL_SCHEDULED")).isEqualTo(80L)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'LOT_EXPIRED'")).isEqualTo(1L)
            assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'FORCE_RELEASED'")).isEqualTo(1L)
        }

    @Test
    fun `경과하지 않은 Lot은 건드리지 않고 임박으로만 바꾼다`() =
        runBlocking<Unit> {
            val reservation: ReservationResult = reserve(30, "ORDER-A")
            databaseClient
                .sql("UPDATE lot SET expiration_date = :date, lot_status = 'NORMAL' WHERE lot_id = :id")
                .bind("date", today.plusDays(20))
                .bind("id", lotId)
                .fetch()
                .rowsUpdated()
                .awaitFirst()

            val result: LotExpirationScanResult = scanLotExpirationUseCase.scan(100)

            assertThat(result.processed).isEqualTo(1)
            assertThat(result.forceReleased).isEqualTo(0)
            assertThat(status(reservation.reservationId)).isEqualTo(0L)
            assertThat(quantity("NORMAL")).isEqualTo(100L)
            assertThat(scalar("SELECT COUNT(*) FROM lot WHERE lot_id = $lotId AND lot_status = 'EXPIRING_SOON'")).isEqualTo(1L)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'LOT_EXPIRED'")).isEqualTo(0L)
        }
}
