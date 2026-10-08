package com.dozycoffee.inventory.reservation.adapter.`in`.scheduler

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
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
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 스케줄러를 켠 컨텍스트에서 실제 MySQL로 만료 스캔을 검증한다. 주기 실행은 길게 늘려 두고 `scan()`을 직접 호출한다 */
@InventoryIntegrationTest
@TestPropertySource(properties = ["inventory.reservation.expiry-scan.enabled=true", "inventory.reservation.expiry-scan.interval=PT24H"])
class ReservationExpirySchedulerIntegrationTest {
    @Autowired
    private lateinit var scheduler: ReservationExpiryScheduler

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            fixture.insertInventory(
                10L,
                productId,
                checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId),
                quantity = 100,
            )
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun reserve(orderId: String): ReservationResult =
        createReservationUseCase.create(
            CreateReservationCommand(
                10L,
                "OMS",
                orderId,
                listOf(CreateReservationCommand.Item(productId, 2)),
                LocalDateTime.now(clock).plusMinutes(30),
                "key-$orderId",
                "svc-oms",
            ),
        )

    private suspend fun expireNow(reservationId: Long) {
        databaseClient
            .sql("UPDATE reservation SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE) WHERE reservation_id = $reservationId")
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

    @Test
    fun `만료된 확정 전 예약만 만료 처리하고 수량을 되돌린다`() =
        runBlocking<Unit> {
            val expired: Long = reserve("EXPIRED").reservationId
            val alive: Long = reserve("ALIVE").reservationId
            val confirmed: Long = reserve("CONFIRMED").reservationId
            confirmReservationUseCase.confirm(confirmed)
            expireNow(expired)
            expireNow(confirmed)

            scheduler.scan()

            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = $expired AND status = 'EXPIRED'")).isEqualTo(1L)
            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = $alive AND status = 'RESERVED'")).isEqualTo(1L)
            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = $confirmed AND status = 'CONFIRMED'")).isEqualTo(1L)
            assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo(4L)
        }

    @Test
    fun `시스템 작업이라 변경한 주체가 system으로 기록된다`() =
        runBlocking<Unit> {
            val id: Long = reserve("EXPIRED").reservationId
            expireNow(id)

            scheduler.scan()

            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE reservation_id = $id AND updated_by = 'system'")).isEqualTo(1L)
            assertThat(
                scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'EXPIRED' AND created_by = 'system'"),
            ).isEqualTo(1L)
        }

    @Test
    fun `한 번에 가져오는 묶음보다 많은 만료 대상도 이어서 모두 처리한다`() =
        runBlocking<Unit> {
            val ids: List<Long> = (1..12).map { reserve("ORDER-$it").reservationId }
            ids.forEach { expireNow(it) }

            scheduler.scan()

            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'EXPIRED'")).isEqualTo(12L)
            assertThat(scalar("SELECT COALESCE(SUM(reserved_quantity), 0) FROM inventory")).isEqualTo(0L)
        }

    @Test
    fun `스캔 여러 개가 동시에 돌아도 각 예약은 한 번만 만료되고 수량은 한 번만 돌아간다`() =
        runBlocking<Unit> {
            (1..5).map { reserve("ORDER-$it").reservationId }.forEach { expireNow(it) }
            reserve("ALIVE")

            (1..5).map { async(Dispatchers.IO) { scheduler.scan() } }.awaitAll()

            assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'EXPIRED'")).isEqualTo(5L)
            assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'EXPIRED'")).isEqualTo(5L)
            assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo(2L)
        }
}
