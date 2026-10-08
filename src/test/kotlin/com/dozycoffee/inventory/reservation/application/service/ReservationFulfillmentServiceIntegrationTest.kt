package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
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

/** 실제 MySQL과 전체 컨텍스트로 출고 확정의 수량, 이력, 결품, 멱등, 롤백, 동시성을 검증한다 */
@InventoryIntegrationTest
class ReservationFulfillmentServiceIntegrationTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var releaseReservationUseCase: ReleaseReservationUseCase

    @Autowired
    private lateinit var fulfillReservationUseCase: FulfillReservationUseCase

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

    /** 6개를 예약해 early 4개, late 2개로 할당되고 확정된 예약 */
    private suspend fun confirmedReservation(orderId: String = "ORDER-1"): ReservationResult {
        val created: ReservationResult =
            createReservationUseCase.create(
                CreateReservationCommand(
                    10L,
                    "OMS",
                    orderId,
                    listOf(CreateReservationCommand.Item(productId, 6)),
                    LocalDateTime.now(clock).plusMinutes(30),
                    "key-$orderId",
                    "svc-oms",
                ),
            )
        return confirmReservationUseCase.confirm(created.reservationId)
    }

    private fun command(
        reservationId: Long,
        earlyShipped: Int,
        lateShipped: Int,
        key: String = "wms-outbound-1",
    ): FulfillReservationCommand =
        FulfillReservationCommand(
            reservationId,
            listOf(FulfillReservationCommand.Allocation(early, earlyShipped), FulfillReservationCommand.Allocation(late, lateShipped)),
            key,
            "svc-wms",
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

    private suspend fun row(
        id: Long,
        column: String,
    ): Long = scalar("SELECT $column FROM inventory WHERE inventory_id = $id")

    @Nested
    inner class `출고 확정` {
        @Test
        fun `전량 출고하면 총 수량과 예약 수량이 함께 줄고 이력과 예약 상태가 반영된다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId

                val result: FulfillmentResult = fulfillReservationUseCase.fulfill(command(id, 4, 2))

                assertThat(result.status).isEqualTo(ReservationStatus.FULFILLED)
                assertThat(result.allocations.map { it.quantityAfter }).containsExactly(0, 8)
                assertThat(row(early, "quantity")).isEqualTo(0L)
                assertThat(row(early, "reserved_quantity")).isEqualTo(0L)
                assertThat(row(late, "quantity")).isEqualTo(8L)
                assertThat(row(late, "reserved_quantity")).isEqualTo(0L)
                assertThat(
                    scalar("SELECT COUNT(*) FROM inventory_history WHERE history_type = 'OUTBOUND' AND reference_id = $id"),
                ).isEqualTo(2L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'FULFILLED'")).isEqualTo(1L)
                assertThat(scalar("SELECT SUM(fulfilled_quantity) FROM reservation_allocation")).isEqualTo(6L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'FULFILLED'")).isEqualTo(1L)
            }

        @Test
        fun `결품은 예약 수량만 되돌리고 총 수량은 그대로이며 그 수량으로 다시 예약할 수 있다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId

                val result: FulfillmentResult = fulfillReservationUseCase.fulfill(command(id, 3, 0))

                assertThat(result.allocations.map { it.shortageQuantity }).containsExactly(1, 2)
                assertThat(result.allocations.map { it.quantityAfter }).containsExactly(1, null)
                assertThat(row(early, "quantity")).isEqualTo(1L)
                assertThat(row(late, "quantity")).isEqualTo(10L)
                assertThat(row(early, "reserved_quantity") + row(late, "reserved_quantity")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(1L)
                assertThat(
                    createReservationUseCase
                        .create(
                            CreateReservationCommand(
                                10L,
                                "OMS",
                                "ORDER-2",
                                listOf(CreateReservationCommand.Item(productId, 11)),
                                LocalDateTime.now(clock).plusMinutes(30),
                                "key-2",
                                "svc-oms",
                            ),
                        ).status,
                ).isEqualTo(ReservationStatus.RESERVED)
            }

        @Test
        fun `출고 수량이 할당을 넘으면 거부하고 아무것도 바뀌지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId

                val e: InvalidDomainValueException = assertThrows { fulfillReservationUseCase.fulfill(command(id, 5, 1)) }

                assertThat(e.errorCode).isEqualTo(ReservationErrorCode.INVALID_FULFILLMENT)

                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'CONFIRMED'")).isEqualTo(1L)
                assertThat(row(early, "quantity")).isEqualTo(4L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `확정 전이거나 해제된 예약은 출고 확정할 수 없다`() =
            runBlocking<Unit> {
                val created: ReservationResult =
                    createReservationUseCase.create(
                        CreateReservationCommand(
                            10L,
                            "OMS",
                            "ORDER-1",
                            listOf(CreateReservationCommand.Item(productId, 6)),
                            LocalDateTime.now(clock).plusMinutes(30),
                            "key-1",
                            "svc-oms",
                        ),
                    )
                assertThrows<InvalidReservationStateException> { fulfillReservationUseCase.fulfill(command(created.reservationId, 4, 2)) }

                confirmReservationUseCase.confirm(created.reservationId)
                releaseReservationUseCase.release(created.reservationId)
                assertThrows<InvalidReservationStateException> { fulfillReservationUseCase.fulfill(command(created.reservationId, 4, 2)) }
                assertThat(row(early, "quantity")).isEqualTo(4L)
            }
    }

    @Nested
    inner class `재요청` {
        @Test
        fun `같은 키와 같은 수량의 재요청은 같은 결과를 주고 수량을 두 번 줄이지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId
                val first: FulfillmentResult = fulfillReservationUseCase.fulfill(command(id, 3, 1))

                val replay: FulfillmentResult = fulfillReservationUseCase.fulfill(command(id, 3, 1))

                assertThat(replay).isEqualTo(first)
                assertThat(row(early, "quantity")).isEqualTo(1L)
                assertThat(row(late, "quantity")).isEqualTo(9L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(2L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'FULFILLED'")).isEqualTo(1L)
            }

        @Test
        fun `다른 키의 재요청은 409이고 다른 수량은 상태 오류다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId
                fulfillReservationUseCase.fulfill(command(id, 3, 1))

                assertThrows<IdempotencyKeyConflictException> { fulfillReservationUseCase.fulfill(command(id, 3, 1, key = "other-key")) }
                assertThrows<InvalidReservationStateException> { fulfillReservationUseCase.fulfill(command(id, 4, 2)) }
                assertThat(row(early, "quantity")).isEqualTo(1L)
            }

        @Test
        fun `같은 키로 20개가 동시에 요청해도 한 번만 반영하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId

                val results: List<Result<FulfillmentResult>> =
                    (1..20)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { runCatching { fulfillReservationUseCase.fulfill(command(id, 3, 1)) } }
                        }.awaitAll()

                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
                assertThat(results.map { it.getOrThrow() }.toSet()).hasSize(1)
                assertThat(row(early, "quantity")).isEqualTo(1L)
                assertThat(row(late, "quantity")).isEqualTo(9L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `출고 확정과 해제가 동시에 일어나도 한쪽만 반영되고 수량이 어긋나지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId

                val fulfills =
                    (1..10).map {
                        async(
                            Dispatchers.IO,
                        ) { runCatching { fulfillReservationUseCase.fulfill(command(id, 4, 2)) } }
                    }
                val releases = (1..10).map { async(Dispatchers.IO) { runCatching { releaseReservationUseCase.release(id) } } }
                (fulfills + releases).awaitAll()

                val status: String =
                    databaseClient
                        .sql("SELECT status FROM reservation")
                        .fetch()
                        .one()
                        .awaitFirst()["status"]
                        .toString()
                assertThat(row(early, "reserved_quantity") + row(late, "reserved_quantity")).isEqualTo(0L)
                when (status) {
                    "FULFILLED" -> assertThat(row(early, "quantity") + row(late, "quantity")).isEqualTo(8L)
                    "RELEASED" -> assertThat(row(early, "quantity") + row(late, "quantity")).isEqualTo(14L)
                    else -> throw AssertionError("예상하지 못한 상태: $status")
                }
            }
    }

    @Nested
    inner class `롤백` {
        @Test
        fun `재고 반영이 실패하면 예약 상태와 출고 수량도 함께 롤백된다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation().reservationId
                databaseClient
                    .sql(
                        "UPDATE inventory SET reserved_quantity = 0 WHERE inventory_id = $late",
                    ).fetch()
                    .rowsUpdated()
                    .awaitFirst()

                assertThrows<InsufficientReservedQuantityException> { fulfillReservationUseCase.fulfill(command(id, 4, 2)) }

                assertThat(scalar("SELECT COUNT(*) FROM reservation WHERE status = 'CONFIRMED'")).isEqualTo(1L)
                assertThat(scalar("SELECT SUM(fulfilled_quantity) FROM reservation_allocation")).isEqualTo(0L)
                assertThat(row(early, "quantity")).isEqualTo(4L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'FULFILLED'")).isEqualTo(0L)
            }
    }
}
