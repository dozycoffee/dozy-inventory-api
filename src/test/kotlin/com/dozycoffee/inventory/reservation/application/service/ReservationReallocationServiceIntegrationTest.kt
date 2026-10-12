package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReallocateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
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

/** 실제 MySQL과 전체 컨텍스트로 Lot 재할당의 수량 이동, 보류, 이력, 이벤트, 멱등, 실패 시 보류 유지, 동시성을 검증한다 */
@InventoryIntegrationTest
class ReservationReallocationServiceIntegrationTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var releaseReservationUseCase: ReleaseReservationUseCase

    @Autowired
    private lateinit var fulfillReservationUseCase: FulfillReservationUseCase

    @Autowired
    private lateinit var reallocateReservationUseCase: ReallocateReservationUseCase

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

    /** [quantity]개를 예약해 확정한다. 처음에는 early 4개, 나머지는 late에 할당된다 */
    private suspend fun confirmedReservation(
        orderId: String = "ORDER-1",
        quantity: Int = 6,
    ): ReservationResult {
        val created: ReservationResult =
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
        return confirmReservationUseCase.confirm(created.reservationId)
    }

    private fun command(
        reservationId: Long,
        shortageInventoryId: Long,
        quantity: Int,
        key: String = "wms-realloc-1",
    ): ReallocateReservationCommand =
        ReallocateReservationCommand(reservationId, listOf(ReallocateReservationCommand.Shortage(shortageInventoryId, quantity)), key)

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

    private suspend fun allocation(
        reservationId: Long,
        inventoryId: Long,
    ): Long? =
        databaseClient
            .sql(
                """
                SELECT ra.quantity FROM reservation_allocation ra JOIN reservation_item ri ON ri.reservation_item_id = ra.reservation_item_id
                 WHERE ri.reservation_id = $reservationId AND ra.inventory_id = $inventoryId
                """,
            ).fetch()
            .one()
            .awaitFirstOrNull()
            ?.values
            ?.first()
            ?.toString()
            ?.toLong()

    private suspend fun reallocatedEvents(reservationId: Long): Long =
        scalar("SELECT COUNT(*) FROM reservation_event WHERE reservation_id = $reservationId AND event_type = 'REALLOCATED'")

    @Nested
    inner class `재할당` {
        @Test
        fun `부족 수량을 다른 Lot으로 옮기고 부족 행을 보류하며 총 수량은 그대로다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()

                val result: ReallocationResult = reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3))

                assertThat(result.heldInventoryIds).containsExactly(early)
                assertThat(
                    result.items
                        .single()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                ).containsExactly(early to 1, late to 5)
                assertThat(
                    result.items
                        .single()
                        .allocations
                        .map { it.lotNumber },
                ).containsExactly("EARLY", "LATE")
                assertThat(allocation(reservation.reservationId, early)).isEqualTo(1L)
                assertThat(allocation(reservation.reservationId, late)).isEqualTo(5L)
                assertThat(row(early, "reserved_quantity")).isEqualTo(1L)
                assertThat(row(late, "reserved_quantity")).isEqualTo(5L)
                assertThat(row(early, "quantity")).isEqualTo(4L)
                assertThat(row(late, "quantity")).isEqualTo(10L)
                assertThat(row(early, "CAST(allocation_hold AS UNSIGNED)")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `전량을 옮기면 그 할당은 사라지고 이력과 Outbox 이벤트를 남긴다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()
                val outboxBefore: Long = scalar("SELECT COUNT(*) FROM outbox_event")

                reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 4))

                assertThat(allocation(reservation.reservationId, early)).isNull()
                assertThat(allocation(reservation.reservationId, late)).isEqualTo(6L)
                assertThat(row(early, "reserved_quantity")).isEqualTo(0L)
                assertThat(reallocatedEvents(reservation.reservationId)).isEqualTo(1L)
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM reservation_event WHERE idempotency_key = 'wms-realloc-1' AND reservation_id = ${reservation.reservationId}",
                    ),
                ).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RESERVATION_REALLOCATED'")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event")).isEqualTo(outboxBefore + 1)
            }

        @Test
        fun `보류된 행은 새 예약의 할당 후보에서 빠진다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation(quantity = 3)
                releaseReservationUseCase.release(confirmedReservation("ORDER-X", 1).reservationId)

                reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3))
                val next: ReservationResult = confirmedReservation("ORDER-2", 2)

                assertThat(
                    next.items
                        .single()
                        .allocations
                        .map { it.inventoryId },
                ).containsExactly(late)
            }

        @Test
        fun `재할당한 예약을 이어서 출고 확정할 수 있다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()
                reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 4))

                fulfillReservationUseCase.fulfill(
                    FulfillReservationCommand(
                        reservation.reservationId,
                        listOf(FulfillReservationCommand.Allocation(late, 6)),
                        "wms-outbound-1",
                        "svc-wms",
                    ),
                )

                assertThat(row(late, "quantity")).isEqualTo(4L)
                assertThat(row(late, "reserved_quantity")).isEqualTo(0L)
                assertThat(row(early, "quantity")).isEqualTo(4L)
            }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `대체 재고가 모자라면 409이고 수량은 그대로지만 보류는 남는다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()
                confirmedReservation("ORDER-2", 8)

                assertThrows<InsufficientAvailableQuantityException> {
                    reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 4))
                }

                assertThat(row(early, "CAST(allocation_hold AS UNSIGNED)")).isEqualTo(1L)
                assertThat(row(early, "reserved_quantity")).isEqualTo(4L)
                assertThat(allocation(reservation.reservationId, early)).isEqualTo(4L)
                assertThat(reallocatedEvents(reservation.reservationId)).isEqualTo(0L)
            }

        @Test
        fun `확정 전이거나 해제된 예약은 거부하고 보류하지 않는다`() =
            runBlocking<Unit> {
                val created: ReservationResult =
                    createReservationUseCase.create(
                        CreateReservationCommand(
                            10L,
                            "OMS",
                            "ORDER-U",
                            listOf(CreateReservationCommand.Item(productId, 2)),
                            LocalDateTime.now(clock).plusMinutes(30),
                            "key-ORDER-U",
                            "svc-oms",
                        ),
                    )
                assertThrows<InvalidReservationStateException> {
                    reallocateReservationUseCase.reallocate(command(created.reservationId, early, 1))
                }
                confirmReservationUseCase.confirm(created.reservationId)
                releaseReservationUseCase.release(created.reservationId)
                assertThrows<InvalidReservationStateException> {
                    reallocateReservationUseCase.reallocate(command(created.reservationId, early, 1, "wms-realloc-2"))
                }

                assertThat(row(early, "CAST(allocation_hold AS UNSIGNED)")).isEqualTo(0L)
            }
    }

    @Nested
    inner class `멱등과 동시성` {
        @Test
        fun `같은 키와 같은 내용의 재요청은 새로 반영하지 않고 같은 결과를 준다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()
                val first: ReallocationResult = reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3))

                val second: ReallocationResult = reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3))

                assertThat(second).isEqualTo(first)
                assertThat(allocation(reservation.reservationId, late)).isEqualTo(5L)
                assertThat(row(late, "reserved_quantity")).isEqualTo(5L)
                assertThat(reallocatedEvents(reservation.reservationId)).isEqualTo(1L)
            }

        @Test
        fun `같은 키에 다른 내용이면 409이고 반영하지 않는다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()
                reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3))

                assertThrows<IdempotencyKeyConflictException> {
                    reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 1))
                }

                assertThat(allocation(reservation.reservationId, early)).isEqualTo(1L)
            }

        @Test
        fun `같은 요청이 동시에 20개 오면 한 번만 반영하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val reservation: ReservationResult = confirmedReservation()

                val results: List<ReallocationResult> =
                    (1..20)
                        .map {
                            async(
                                Dispatchers.Default,
                            ) { reallocateReservationUseCase.reallocate(command(reservation.reservationId, early, 3)) }
                        }.awaitAll()

                assertThat(results.toSet()).hasSize(1)
                assertThat(reallocatedEvents(reservation.reservationId)).isEqualTo(1L)
                assertThat(allocation(reservation.reservationId, early)).isEqualTo(1L)
                assertThat(allocation(reservation.reservationId, late)).isEqualTo(5L)
                assertThat(row(late, "reserved_quantity")).isEqualTo(5L)
                assertThat(row(early, "reserved_quantity")).isEqualTo(1L)
            }
    }
}
