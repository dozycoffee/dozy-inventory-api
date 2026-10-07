package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateOrderReservationException
import com.dozycoffee.inventory.reservation.domain.exception.ProductNotReservableException
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

/** 실제 MySQL과 전체 컨텍스트로 예약 생성의 트랜잭션, 멱등, 동시성을 검증한다 */
@InventoryIntegrationTest
class ReservationServiceIntegrationTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

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

    private fun command(
        quantity: Int = 6,
        orderId: String = "ORDER-1",
        key: String = "svc-oms-order-1",
        product: Long = productId,
    ): CreateReservationCommand =
        CreateReservationCommand(
            10L,
            "OMS",
            orderId,
            listOf(CreateReservationCommand.Item(product, quantity)),
            LocalDateTime.now(clock).plusMinutes(30),
            key,
            "svc-oms",
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

    private suspend fun reserved(inventoryId: Long): Long =
        scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $inventoryId")

    @Nested
    inner class `생성` {
        @Test
        fun `유통기한이 이른 Lot부터 예약 수량이 늘고 예약과 이력이 저장된다`() =
            runBlocking<Unit> {
                val result: ReservationResult = createReservationUseCase.create(command(quantity = 6))

                assertThat(result.status).isEqualTo(ReservationStatus.RESERVED)
                assertThat(
                    result.items
                        .single()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                ).containsExactly(early to 4, late to 2)
                assertThat(reserved(early)).isEqualTo(4)
                assertThat(reserved(late)).isEqualTo(2)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_allocation")).isEqualTo(2L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'CREATED'")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `가용 수량이 모자라면 아무것도 남지 않는다`() =
            runBlocking<Unit> {
                assertThrows<InsufficientAvailableQuantityException> { createReservationUseCase.create(command(quantity = 15)) }

                assertThat(reserved(early) + reserved(late)).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(0L)
            }

        @Test
        fun `비활성 상품은 거부하고 아무것도 잡지 않는다`() =
            runBlocking<Unit> {
                databaseClient
                    .sql("UPDATE product SET product_status = 'INACTIVE'")
                    .fetch()
                    .rowsUpdated()
                    .awaitFirst()

                assertThrows<ProductNotReservableException> { createReservationUseCase.create(command()) }

                assertThat(reserved(early) + reserved(late)).isEqualTo(0L)
            }
    }

    @Nested
    inner class `중복 주문` {
        @Test
        fun `같은 주문에 다른 멱등 키로 다시 예약하면 거부하고 수량을 더 잡지 않는다`() =
            runBlocking<Unit> {
                createReservationUseCase.create(command(quantity = 3))

                assertThrows<DuplicateOrderReservationException> {
                    createReservationUseCase.create(
                        command(quantity = 3, key = "other-key"),
                    )
                }

                assertThat(reserved(early) + reserved(late)).isEqualTo(3L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(1L)
            }

        @Test
        fun `만료 시각이 지난 예약만 있으면 같은 주문을 다시 예약할 수 있다`() =
            runBlocking<Unit> {
                createReservationUseCase.create(command(quantity = 3))
                databaseClient
                    .sql(
                        "UPDATE reservation SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 HOUR)",
                    ).fetch()
                    .rowsUpdated()
                    .awaitFirst()

                val result: ReservationResult = createReservationUseCase.create(command(quantity = 3, key = "other-key"))

                assertThat(result.status).isEqualTo(ReservationStatus.RESERVED)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(2L)
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 키의 재요청은 같은 예약을 반환하고 수량을 중복으로 잡지 않는다`() =
            runBlocking<Unit> {
                val first: ReservationResult = createReservationUseCase.create(command(quantity = 6))
                val replay: ReservationResult = createReservationUseCase.create(command(quantity = 6))

                assertThat(replay).isEqualTo(first)
                assertThat(reserved(early) + reserved(late)).isEqualTo(6L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(1L)
            }

        @Test
        fun `같은 키에 다른 내용이면 거부한다`() =
            runBlocking<Unit> {
                createReservationUseCase.create(command(quantity = 6))

                assertThrows<IdempotencyKeyConflictException> { createReservationUseCase.create(command(quantity = 7)) }
                assertThat(reserved(early) + reserved(late)).isEqualTo(6L)
            }

        @Test
        fun `같은 키를 20개가 동시에 요청해도 예약은 하나이고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val results: List<Result<ReservationResult>> =
                    (1..20)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { runCatching { createReservationUseCase.create(command(quantity = 6)) } }
                        }.awaitAll()

                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() }).isEmpty()
                assertThat(results.map { it.getOrThrow().reservationId }.toSet()).hasSize(1)
                assertThat(reserved(early) + reserved(late)).isEqualTo(6L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `동시성` {
        private suspend fun createOrder(i: Int): Result<ReservationResult> =
            runCatching { createReservationUseCase.create(command(quantity = 3, orderId = "ORDER-$i", key = "key-$i")) }

        @Test
        fun `서로 다른 주문 20개가 동시에 3개씩 예약해도 재고 14개를 넘겨 잡지 않는다`() =
            runBlocking<Unit> {
                val results: List<Result<ReservationResult>> = (1..20).map { i: Int -> async(Dispatchers.IO) { createOrder(i) } }.awaitAll()

                val succeeded: Int = results.count { it.isSuccess }
                assertThat(succeeded).isBetween(1, 4)
                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull() })
                    .allMatch { it is InsufficientAvailableQuantityException || it is AllocationConflictException }
                assertThat(reserved(early) + reserved(late)).isEqualTo(3L * succeeded)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(succeeded.toLong())
                assertThat(scalar("SELECT COALESCE(SUM(quantity), 0) FROM reservation_allocation")).isEqualTo(3L * succeeded)
            }

        @Test
        fun `경합으로 밀린 호출자가 다시 요청하면 재고가 허용하는 4개가 정확히 성공한다`() =
            runBlocking<Unit> {
                val results: List<Result<ReservationResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(Dispatchers.IO) {
                                var result: Result<ReservationResult> = createOrder(i)
                                repeat(CALLER_RETRIES) {
                                    if (result.exceptionOrNull() is AllocationConflictException) result = createOrder(i)
                                }
                                result
                            }
                        }.awaitAll()

                assertThat(results.count { it.isSuccess }).isEqualTo(4)
                assertThat(
                    results.filter { it.isFailure }.map { it.exceptionOrNull() },
                ).allMatch { it is InsufficientAvailableQuantityException }
                assertThat(reserved(early) + reserved(late)).isEqualTo(12L)
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo(4L)
            }
    }

    private companion object {
        const val CALLER_RETRIES: Int = 5
    }
}
