package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateReservationKeyException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@DataR2dbcTest
@ActiveProfiles("local")
@Import(
    ReservationPersistenceAdapter::class,
    ReservationEventPersistenceAdapter::class,
    LotPersistenceAdapter::class,
    R2dbcConfig::class,
    ClockConfig::class,
    LocalActorProvider::class,
)
class ReservationPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: ReservationPersistenceAdapter

    @Autowired
    private lateinit var eventAdapter: ReservationEventPersistenceAdapter

    @Autowired
    private lateinit var lotAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var otherProductId: Long = 0L
    private var firstInventoryId: Long = 0L
    private var secondInventoryId: Long = 0L
    private var otherInventoryId: Long = 0L
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            otherProductId = fixture.seedProduct("BEAN-002")
            val lotA: Long = checkNotNull(fixture.seedLot(productId, "A").lotId)
            val lotB: Long = checkNotNull(fixture.seedLot(productId, "B").lotId)
            val lotC: Long = checkNotNull(fixture.seedLot(otherProductId, "C").lotId)
            firstInventoryId = fixture.insertInventory(10L, productId, lotA)
            secondInventoryId = fixture.insertInventory(10L, productId, lotB)
            otherInventoryId = fixture.insertInventory(10L, otherProductId, lotC)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun reservation(
        key: String = "svc-oms-order-1",
        orderId: String = "ORDER-1",
    ): Reservation =
        Reservation.create(
            warehouseId = 10L,
            channel = ReservationChannel.of("OMS"),
            externalOrderId = ExternalOrderId.of(orderId),
            expiry = ReservationExpiry.create(now.plusMinutes(30), now.plusHours(1), now),
            idempotencyKey = IdempotencyKey.of(key),
            requesterService = RequesterService.of("svc-oms"),
            items =
                listOf(
                    ReservationItem.create(
                        productId,
                        10,
                        listOf(ReservationAllocation.create(secondInventoryId, 4), ReservationAllocation.create(firstInventoryId, 6)),
                    ),
                    ReservationItem.create(otherProductId, 3, listOf(ReservationAllocation.create(otherInventoryId, 3))),
                ),
        )

    private suspend fun count(table: String): Long =
        databaseClient
            .sql("SELECT COUNT(*) AS c FROM $table")
            .fetch()
            .one()
            .awaitFirst()["c"]
            .toString()
            .toLong()

    @Nested
    inner class `저장과 조회` {
        @Test
        fun `예약과 항목과 할당을 함께 저장하고 같은 모양으로 다시 읽는다`() =
            runBlocking<Unit> {
                val saved: Reservation = adapter.save(reservation())
                val loaded: Reservation = checkNotNull(adapter.findById(checkNotNull(saved.reservationId)))

                assertThat(loaded.reservationId).isNotNull()
                assertThat(loaded.status).isEqualTo(ReservationStatus.RESERVED)
                assertThat(loaded.warehouseId).isEqualTo(10L)
                assertThat(loaded.channel.value).isEqualTo("OMS")
                assertThat(loaded.externalOrderId.value).isEqualTo("ORDER-1")
                assertThat(loaded.expiry.expiresAt).isEqualTo(now.plusMinutes(30))
                assertThat(loaded.expiry.maxExpiresAt).isEqualTo(now.plusHours(1))
                assertThat(loaded.confirmedAt).isNull()
                assertThat(loaded.idempotencyKey.value).isEqualTo("svc-oms-order-1")
                assertThat(loaded.requesterService.value).isEqualTo("svc-oms")
                assertThat(loaded.items.map { it.productId to it.requestedQuantity }).containsExactly(productId to 10, otherProductId to 3)
                val allocations: List<ReservationAllocation> = loaded.items.first().allocations
                assertThat(allocations.map { it.inventoryId to it.quantity }).containsExactly(firstInventoryId to 6, secondInventoryId to 4)
                assertThat(allocations).allMatch { it.fulfilledQuantity == 0 && it.reservationAllocationId != null }
                assertThat(saved).isEqualTo(loaded)
                assertThat(count("reservation")).isEqualTo(1L)
                assertThat(count("reservation_item")).isEqualTo(2L)
                assertThat(count("reservation_allocation")).isEqualTo(3L)
            }

        @Test
        fun `멱등 키로 조회하고 없으면 null이다`() =
            runBlocking<Unit> {
                val saved: Reservation = adapter.save(reservation())

                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("svc-oms-order-1"))).isEqualTo(saved)
                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("other"))).isNull()
                assertThat(adapter.findById(999_999L)).isNull()
            }

        @Test
        fun `감사 컬럼이 채워진다`() =
            runBlocking<Unit> {
                adapter.save(reservation())

                val row: Map<String, Any?> =
                    databaseClient
                        .sql("SELECT created_by, updated_by, created_at FROM reservation")
                        .fetch()
                        .one()
                        .awaitFirst()
                assertThat(row["created_by"] as String).isNotBlank()
                assertThat(row["updated_by"] as String).isNotBlank()
                assertThat(row["created_at"]).isNotNull()
            }

        @Test
        fun `이미 저장된 예약은 다시 저장할 수 없다`() =
            runBlocking<Unit> {
                val saved: Reservation = adapter.save(reservation())

                assertThrows<IllegalStateException> { adapter.save(saved) }
            }
    }

    @Nested
    inner class `멱등 키 중복` {
        @Test
        fun `같은 멱등 키는 DuplicateReservationKeyException이고 항목과 할당은 저장되지 않는다`() =
            runBlocking<Unit> {
                adapter.save(reservation())

                assertThrows<DuplicateReservationKeyException> { adapter.save(reservation(orderId = "ORDER-2")) }

                assertThat(count("reservation")).isEqualTo(1L)
                assertThat(count("reservation_item")).isEqualTo(2L)
            }

        @Test
        fun `같은 키를 50개가 동시에 저장하면 정확히 하나만 성공한다`() =
            runBlocking<Unit> {
                val failures: List<Throwable?> =
                    (1..50)
                        .map { i: Int ->
                            async(Dispatchers.Default) { runCatching { adapter.save(reservation(orderId = "ORDER-$i")) }.exceptionOrNull() }
                        }.awaitAll()

                assertThat(failures.count { it == null }).isEqualTo(1)
                assertThat(failures.filterNotNull()).allMatch { it is DuplicateReservationKeyException }
                assertThat(count("reservation")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `예약 이력` {
        @Test
        fun `JSON 상세와 함께 저장하고 순서대로 읽는다`() =
            runBlocking<Unit> {
                val reservationId: Long = checkNotNull(adapter.save(reservation()).reservationId)

                eventAdapter.save(
                    ReservationEvent.create(reservationId, ReservationEventType.CREATED, """{"expiresAt":"2026-10-07T12:30:00"}"""),
                )
                eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.CONFIRMED, null))

                val events: List<ReservationEvent> = eventAdapter.findAllByReservationId(reservationId)
                assertThat(events.map { it.eventType }).containsExactly(ReservationEventType.CREATED, ReservationEventType.CONFIRMED)
                assertThat(events.first().detail).contains("2026-10-07T12:30:00")
                assertThat(events.last().detail).isNull()
                assertThat(events).allMatch { it.reservationEventId != null }
            }

        @Test
        fun `JSON이 아닌 상세는 저장할 수 없다`() =
            runBlocking<Unit> {
                val reservationId: Long = checkNotNull(adapter.save(reservation()).reservationId)

                assertThrows<Exception> {
                    eventAdapter.save(
                        ReservationEvent.create(reservationId, ReservationEventType.CREATED, "not json"),
                    )
                }
            }

        @Test
        fun `이미 저장된 이력은 다시 저장할 수 없다`() =
            runBlocking<Unit> {
                val reservationId: Long = checkNotNull(adapter.save(reservation()).reservationId)
                val saved: ReservationEvent = eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.CREATED, null))

                assertThrows<IllegalStateException> { eventAdapter.save(saved) }
            }
    }

    @Nested
    inner class `스키마` {
        private suspend fun checkValues(constraint: String): List<String> {
            val clause: String =
                databaseClient
                    .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = :name")
                    .bind("name", constraint)
                    .fetch()
                    .one()
                    .awaitFirst()["CHECK_CLAUSE"] as String
            return Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()
        }

        @Test
        fun `ReservationStatus와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_reservation_status"),
                ).containsExactlyInAnyOrderElementsOf(ReservationStatus.entries.map { it.name })
            }

        @Test
        fun `ReservationEventType과 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_reservation_event_type"),
                ).containsExactlyInAnyOrderElementsOf(ReservationEventType.entries.map { it.name })
            }
    }
}
