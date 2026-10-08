package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExpireReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExtendReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationExpiredException
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
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 실제 MySQL과 전체 컨텍스트로 예약 확정·해제·연장·만료의 트랜잭션, 수량 복원, 동시성을 검증한다 */
@InventoryIntegrationTest
class ReservationTransitionServiceIntegrationTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var releaseReservationUseCase: ReleaseReservationUseCase

    @Autowired
    private lateinit var extendReservationUseCase: ExtendReservationUseCase

    @Autowired
    private lateinit var expireReservationUseCase: ExpireReservationUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var early: Long = 0L
    private var late: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            early =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "EARLY", LocalDate.of(2027, 1, 1)).lotId),
                    quantity = 4,
                )
            late =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "LATE", LocalDate.of(2027, 6, 1)).lotId),
                    quantity = 10,
                )
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun reserve(
        quantity: Int = 6,
        orderId: String = "ORDER-1",
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

    private suspend fun totalReserved(): Long = scalar("SELECT COALESCE(SUM(reserved_quantity), 0) FROM inventory")

    private suspend fun events(type: String): Long = scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = '$type'")

    private suspend fun expireNow(reservationId: Long) {
        databaseClient
            .sql("UPDATE reservation SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE) WHERE reservation_id = $reservationId")
            .fetch()
            .rowsUpdated()
            .awaitFirst()
    }

    @Nested
    inner class `확정` {
        @Test
        fun `확정하면 CONFIRMED가 되고 만료 시각이 사라지며 예약 수량은 그대로다`() =
            runBlocking<Unit> {
                val created: ReservationResult = reserve()

                val confirmed: ReservationResult = confirmReservationUseCase.confirm(created.reservationId)

                assertThat(confirmed.status).isEqualTo(ReservationStatus.CONFIRMED)
                assertThat(confirmed.expiresAt).isNull()
                assertThat(confirmed.items).isEqualTo(created.items)
                assertThat(totalReserved()).isEqualTo(6L)
                assertThat(events("CONFIRMED")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE confirmed_at IS NOT NULL")).isEqualTo(1L)
            }

        @Test
        fun `같은 예약을 20개가 동시에 확정해도 모두 성공하고 이력은 한 번이다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId

                val results: List<Result<ReservationResult>> =
                    (1..20).map { async(Dispatchers.IO) { runCatching { confirmReservationUseCase.confirm(id) } } }.awaitAll()

                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
                assertThat(results.map { it.getOrThrow().status }.toSet()).containsExactly(ReservationStatus.CONFIRMED)
                assertThat(events("CONFIRMED")).isEqualTo(1L)
            }

        @Test
        fun `만료 시각이 지난 예약은 확정할 수 없고 상태가 그대로다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                expireNow(id)

                assertThrows<ReservationExpiredException> { confirmReservationUseCase.confirm(id) }

                assertThat(events("CONFIRMED")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'RESERVED'")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `해제` {
        @Test
        fun `확정 전 예약을 해제하면 예약 수량이 가용 수량으로 돌아온다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId

                val released: ReservationResult = releaseReservationUseCase.release(id)

                assertThat(released.status).isEqualTo(ReservationStatus.RELEASED)
                assertThat(totalReserved()).isEqualTo(0L)
                assertThat(events("RELEASED")).isEqualTo(1L)
                assertThat(
                    reserve(quantity = 14, orderId = "ORDER-2")
                        .items
                        .single()
                        .allocations
                        .sumOf { it.quantity },
                ).isEqualTo(14)
            }

        @Test
        fun `확정된 예약도 해제할 수 있다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                confirmReservationUseCase.confirm(id)

                assertThat(releaseReservationUseCase.release(id).status).isEqualTo(ReservationStatus.RELEASED)
                assertThat(totalReserved()).isEqualTo(0L)
            }

        @Test
        fun `다시 해제해도 수량을 두 번 되돌리지 않는다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                releaseReservationUseCase.release(id)
                reserve(quantity = 5, orderId = "ORDER-2")

                assertThat(releaseReservationUseCase.release(id).status).isEqualTo(ReservationStatus.RELEASED)

                assertThat(totalReserved()).isEqualTo(5L)
                assertThat(events("RELEASED")).isEqualTo(1L)
            }

        @Test
        fun `같은 예약을 20개가 동시에 해제해도 수량은 한 번만 되돌아간다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                reserve(quantity = 3, orderId = "ORDER-2")

                val results: List<Result<ReservationResult>> =
                    (1..20).map { async(Dispatchers.IO) { runCatching { releaseReservationUseCase.release(id) } } }.awaitAll()

                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
                assertThat(totalReserved()).isEqualTo(3L)
                assertThat(events("RELEASED")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `연장` {
        @Test
        fun `만료 시각을 최대 만료 시각 안에서 늘린다`() =
            runBlocking<Unit> {
                val created: ReservationResult = reserve()
                val newExpiresAt: LocalDateTime = created.expiresAt!!.plusMinutes(20)

                val extended: ReservationResult =
                    extendReservationUseCase.extend(
                        ExtendReservationCommand(created.reservationId, newExpiresAt),
                    )

                assertThat(extended.expiresAt).isEqualTo(newExpiresAt)
                assertThat(events("EXTENDED")).isEqualTo(1L)
            }

        @Test
        fun `최대 만료 시각을 넘으면 거부하고 만료 시각은 그대로다`() =
            runBlocking<Unit> {
                val created: ReservationResult = reserve()

                val e: InvalidDomainValueException =
                    assertThrows {
                        extendReservationUseCase.extend(
                            ExtendReservationCommand(created.reservationId, created.maxExpiresAt.plusSeconds(1)),
                        )
                    }

                assertThat(e.errorCode).isEqualTo(ReservationErrorCode.INVALID_RESERVATION_EXPIRY)

                assertThat(events("EXTENDED")).isEqualTo(0L)
            }
    }

    @Nested
    inner class `만료` {
        @Test
        fun `만료된 예약을 만료 처리하면 EXPIRED가 되고 수량이 돌아온다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                expireNow(id)

                assertThat(expireReservationUseCase.expire(id)).isTrue()

                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'EXPIRED'")).isEqualTo(1L)
                assertThat(totalReserved()).isEqualTo(0L)
                assertThat(events("EXPIRED")).isEqualTo(1L)
            }

        @Test
        fun `만료 전이거나 이미 만료·확정된 예약은 처리하지 않는다`() =
            runBlocking<Unit> {
                val notYet: Long = reserve().reservationId
                assertThat(expireReservationUseCase.expire(notYet)).isFalse()

                val confirmed: Long = reserve(quantity = 2, orderId = "ORDER-2").reservationId
                confirmReservationUseCase.confirm(confirmed)
                expireNow(confirmed)
                assertThat(expireReservationUseCase.expire(confirmed)).isFalse()

                assertThat(expireReservationUseCase.expire(999_999L)).isFalse()
                assertThat(totalReserved()).isEqualTo(8L)
            }

        @Test
        fun `같은 예약을 20개가 동시에 만료하려 하면 정확히 하나만 처리하고 수량은 한 번만 돌아간다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                reserve(quantity = 3, orderId = "ORDER-2")
                expireNow(id)

                val results: List<Boolean> = (1..20).map { async(Dispatchers.IO) { expireReservationUseCase.expire(id) } }.awaitAll()

                assertThat(results.count { it }).isEqualTo(1)
                assertThat(totalReserved()).isEqualTo(3L)
                assertThat(events("EXPIRED")).isEqualTo(1L)
            }

        @Test
        fun `만료와 해제가 동시에 일어나도 수량은 한 번만 돌아간다`() =
            runBlocking<Unit> {
                val id: Long = reserve().reservationId
                reserve(quantity = 3, orderId = "ORDER-2")
                expireNow(id)

                val expire = (1..10).map { async(Dispatchers.IO) { runCatching { expireReservationUseCase.expire(id) } } }
                val release = (1..10).map { async(Dispatchers.IO) { runCatching { releaseReservationUseCase.release(id) } } }
                (expire + release).awaitAll()

                assertThat(totalReserved()).isEqualTo(3L)
                assertThat(events("EXPIRED") + events("RELEASED")).isEqualTo(1L)
            }
    }
}
