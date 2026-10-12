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
import com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException
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
import kotlinx.coroutines.reactive.awaitSingle
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
    inner class `살아 있는 예약 확인` {
        private suspend fun changeTo(
            status: String,
            expiresAt: LocalDateTime?,
        ) {
            databaseClient
                .sql("UPDATE reservation SET status = :status, expires_at = :expiresAt")
                .bind("status", status)
                .let { spec ->
                    if (expiresAt ==
                        null
                    ) {
                        spec.bindNull("expiresAt", LocalDateTime::class.java)
                    } else {
                        spec.bind("expiresAt", expiresAt)
                    }
                }.fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        private suspend fun active(
            channel: String = "OMS",
            orderId: String = "ORDER-1",
        ): Boolean = adapter.existsActive(ReservationChannel.of(channel), ExternalOrderId.of(orderId), now)

        @Test
        fun `만료 전의 확정 전 예약과 확정된 예약은 살아 있다`() =
            runBlocking<Unit> {
                adapter.save(reservation())
                assertThat(active()).isTrue()

                changeTo("CONFIRMED", null)
                assertThat(active()).isTrue()
            }

        @Test
        fun `만료 시각이 지난 확정 전 예약은 살아 있지 않다`() =
            runBlocking<Unit> {
                adapter.save(reservation())

                changeTo("RESERVED", now.minusMinutes(1))
                assertThat(active()).isFalse()

                changeTo("RESERVED", now)
                assertThat(active()).isFalse()
            }

        @Test
        fun `해제, 만료, 출고 완료된 예약은 살아 있지 않다`() =
            runBlocking<Unit> {
                adapter.save(reservation())

                listOf("RELEASED", "EXPIRED", "FULFILLED").forEach { status: String ->
                    changeTo(status, null)
                    assertThat(active()).describedAs(status).isFalse()
                }
            }

        @Test
        fun `다른 채널이거나 다른 주문이면 살아 있지 않다`() =
            runBlocking<Unit> {
                adapter.save(reservation())

                assertThat(active(channel = "STORE")).isFalse()
                assertThat(active(orderId = "ORDER-2")).isFalse()
            }
    }

    @Nested
    inner class `상태 갱신` {
        private suspend fun saved(
            key: String = "svc-oms-order-1",
            orderId: String = "ORDER-1",
        ): Reservation = adapter.save(reservation(key, orderId))

        @Test
        fun `확정하면 상태와 확정 시각을 저장하고 만료 시각을 비운다`() =
            runBlocking<Unit> {
                val reservation: Reservation = saved()
                reservation.confirm(now.plusMinutes(1))

                assertThat(adapter.updateState(reservation, ReservationStatus.RESERVED)).isTrue()

                val loaded: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                assertThat(loaded.status).isEqualTo(ReservationStatus.CONFIRMED)
                assertThat(loaded.expiry.expiresAt).isNull()
                assertThat(loaded.expiry.maxExpiresAt).isEqualTo(now.plusHours(1))
                assertThat(loaded.confirmedAt).isEqualTo(now.plusMinutes(1))
            }

        @Test
        fun `연장하면 만료 시각을 저장한다`() =
            runBlocking<Unit> {
                val reservation: Reservation = saved()
                reservation.extend(now.plusMinutes(50), now)

                assertThat(adapter.updateState(reservation, ReservationStatus.RESERVED)).isTrue()

                assertThat(
                    checkNotNull(adapter.findById(checkNotNull(reservation.reservationId))).expiry.expiresAt,
                ).isEqualTo(now.plusMinutes(50))
            }

        @Test
        fun `저장된 상태가 기대한 상태가 아니면 아무것도 바꾸지 않고 false다`() =
            runBlocking<Unit> {
                val reservation: Reservation = saved()
                val other: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                other.release()
                assertThat(adapter.updateState(other, ReservationStatus.RESERVED)).isTrue()

                reservation.confirm(now.plusMinutes(1))

                assertThat(adapter.updateState(reservation, ReservationStatus.RESERVED)).isFalse()
                val loaded: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                assertThat(loaded.status).isEqualTo(ReservationStatus.RELEASED)
                assertThat(loaded.confirmedAt).isNull()
            }

        @Test
        fun `같은 예약을 50개가 동시에 확정하려 하면 정확히 하나만 성공한다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(saved().reservationId)

                val results: List<Boolean> =
                    (1..50)
                        .map {
                            async(Dispatchers.Default) {
                                val reservation: Reservation = checkNotNull(adapter.findById(id))
                                reservation.confirm(now.plusMinutes(1))
                                adapter.updateState(reservation, ReservationStatus.RESERVED)
                            }
                        }.awaitAll()

                assertThat(results.count { it }).isEqualTo(1)
                assertThat(checkNotNull(adapter.findById(id)).status).isEqualTo(ReservationStatus.CONFIRMED)
            }

        @Test
        fun `수정 시각과 수정자를 갱신한다`() =
            runBlocking<Unit> {
                val reservation: Reservation = saved()
                databaseClient
                    .sql(
                        "UPDATE reservation SET updated_by = 'before', updated_at = '2020-01-01 00:00:00'",
                    ).fetch()
                    .rowsUpdated()
                    .awaitSingle()
                reservation.release()

                adapter.updateState(reservation, ReservationStatus.RESERVED)

                val row: Map<String, Any?> =
                    databaseClient
                        .sql("SELECT updated_by, updated_at FROM reservation")
                        .fetch()
                        .one()
                        .awaitFirst()
                assertThat(row["updated_by"] as String).isNotEqualTo("before")
                assertThat(row["updated_at"].toString()).doesNotStartWith("2020")
            }

        @Test
        fun `저장되지 않은 예약은 갱신할 수 없다`() =
            runBlocking<Unit> {
                assertThrows<IllegalStateException> { adapter.updateState(reservation(), ReservationStatus.RESERVED) }
            }
    }

    @Nested
    inner class `출고 확정 저장` {
        private suspend fun confirmedReservation(): Reservation {
            val saved: Reservation = adapter.save(reservation())
            saved.confirm(now.plusMinutes(1))
            assertThat(adapter.updateState(saved, ReservationStatus.RESERVED)).isTrue()
            return checkNotNull(adapter.findById(checkNotNull(saved.reservationId)))
        }

        @Test
        fun `상태와 할당별 출고 수량을 함께 저장한다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val allocations: List<ReservationAllocation> = reservation.items.first().allocations
                reservation.fulfill(
                    reservation.items.flatMap { it.allocations }.associate {
                        it.inventoryId to
                            (if (it.inventoryId == allocations.first().inventoryId) it.quantity else 0)
                    },
                )

                assertThat(adapter.updateFulfillment(reservation, ReservationStatus.CONFIRMED)).isTrue()

                val loaded: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                assertThat(loaded.status).isEqualTo(ReservationStatus.FULFILLED)
                val loadedAllocations: List<ReservationAllocation> = loaded.items.flatMap { it.allocations }
                assertThat(loadedAllocations.map { it.fulfilledQuantity }).containsExactly(6, 0, 0)
                assertThat(loadedAllocations.map { it.shortageQuantity }).containsExactly(0, 4, 3)
            }

        @Test
        fun `그 사이 상태가 바뀌었으면 아무것도 바꾸지 않고 false다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val other: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                other.release()
                assertThat(adapter.updateState(other, ReservationStatus.CONFIRMED)).isTrue()
                reservation.fulfill(reservation.items.flatMap { it.allocations }.associate { it.inventoryId to it.quantity })

                assertThat(adapter.updateFulfillment(reservation, ReservationStatus.CONFIRMED)).isFalse()

                val loaded: Reservation = checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))
                assertThat(loaded.status).isEqualTo(ReservationStatus.RELEASED)
                assertThat(loaded.items.flatMap { it.allocations }.map { it.fulfilledQuantity }).containsOnly(0)
            }

        @Test
        fun `같은 예약을 50개가 동시에 출고 확정하려 하면 정확히 하나만 성공한다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(confirmedReservation().reservationId)

                val results: List<Boolean> =
                    (1..50)
                        .map {
                            async(Dispatchers.Default) {
                                val reservation: Reservation = checkNotNull(adapter.findById(id))
                                reservation.fulfill(
                                    reservation.items.flatMap { it.allocations }.associate { it.inventoryId to it.quantity },
                                )
                                adapter.updateFulfillment(reservation, ReservationStatus.CONFIRMED)
                            }
                        }.awaitAll()

                assertThat(results.count { it }).isEqualTo(1)
                assertThat(checkNotNull(adapter.findById(id)).status).isEqualTo(ReservationStatus.FULFILLED)
            }

        @Test
        fun `출고 수량 갱신에도 수정 시각과 수정자가 기록된다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                databaseClient
                    .sql("UPDATE reservation_allocation SET updated_by = 'before'")
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                reservation.fulfill(reservation.items.flatMap { it.allocations }.associate { it.inventoryId to it.quantity })

                adapter.updateFulfillment(reservation, ReservationStatus.CONFIRMED)

                assertThat(
                    databaseClient
                        .sql(
                            "SELECT COUNT(*) AS c FROM reservation_allocation WHERE updated_by = 'before'",
                        ).fetch()
                        .one()
                        .awaitFirst()["c"]
                        .toString(),
                ).isEqualTo("0")
            }
    }

    @Nested
    inner class `재할당 저장` {
        private suspend fun confirmedReservation(): Reservation {
            val saved: Reservation = adapter.save(reservation())
            saved.confirm(now.plusMinutes(1))
            assertThat(adapter.updateState(saved, ReservationStatus.RESERVED)).isTrue()
            return checkNotNull(adapter.findById(checkNotNull(saved.reservationId)))
        }

        private fun quantities(reservation: Reservation): Map<Long, Int> =
            reservation.items.flatMap { it.allocations }.associate { it.inventoryId to it.quantity }

        private fun before(reservation: Reservation): Map<Long, Int> =
            reservation.items.flatMap { it.allocations }.associate { checkNotNull(it.reservationAllocationId) to it.quantity }

        private suspend fun reload(reservation: Reservation): Reservation =
            checkNotNull(adapter.findById(checkNotNull(reservation.reservationId)))

        @Test
        fun `저장된 수량이 기대와 정확히 같을 때만 할당을 잠그고 true다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val expected: Map<Long, Int> = before(reservation)

                assertThat(adapter.lockAllocations(reservation, expected)).isTrue()
                assertThat(adapter.lockAllocations(reservation, expected.mapValues { it.value + 1 })).isFalse()
                assertThat(adapter.lockAllocations(reservation, expected.minus(expected.keys.first()))).isFalse()
                assertThat(adapter.lockAllocations(reservation, expected.plus(999_999L to 1))).isFalse()
            }

        @Test
        fun `수량 변경과 새 할당 추가를 함께 저장하고 상태는 건드리지 않는다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val expected: Map<Long, Int> = before(reservation)
                val spare: Long = fixture.insertInventory(10L, productId, checkNotNull(fixture.seedLot(productId, "D").lotId))
                reservation.reallocate(mapOf(firstInventoryId to 2), mapOf(productId to listOf(spare to 2)))

                assertThat(adapter.updateAllocations(reservation, expected)).isTrue()

                val loaded: Reservation = reload(reservation)
                assertThat(loaded.status).isEqualTo(ReservationStatus.CONFIRMED)
                assertThat(
                    quantities(loaded),
                ).containsEntry(firstInventoryId, 4).containsEntry(spare, 2).containsEntry(secondInventoryId, 4)
            }

        @Test
        fun `수량이 0이 된 할당은 지운다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val expected: Map<Long, Int> = before(reservation)
                reservation.reallocate(mapOf(firstInventoryId to 6), mapOf(productId to listOf(secondInventoryId to 6)))

                assertThat(adapter.updateAllocations(reservation, expected)).isTrue()

                assertThat(quantities(reload(reservation))).containsEntry(secondInventoryId, 10).doesNotContainKey(firstInventoryId)
                assertThat(count("reservation_allocation")).isEqualTo(2L)
            }

        @Test
        fun `그 사이 다른 요청이 수량을 바꿨으면 아무것도 바꾸지 않고 false다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val expected: Map<Long, Int> = before(reservation)
                databaseClient
                    .sql("UPDATE reservation_allocation SET quantity = quantity - 1 WHERE inventory_id = $firstInventoryId")
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()
                reservation.reallocate(mapOf(firstInventoryId to 2), mapOf(productId to listOf(secondInventoryId to 2)))

                assertThat(adapter.updateAllocations(reservation, expected)).isFalse()

                assertThat(quantities(reload(reservation))).containsEntry(firstInventoryId, 5).containsEntry(secondInventoryId, 4)
            }

        @Test
        fun `수정 시각과 수정자를 갱신한다`() =
            runBlocking<Unit> {
                val reservation: Reservation = confirmedReservation()
                val expected: Map<Long, Int> = before(reservation)
                reservation.reallocate(mapOf(firstInventoryId to 2), mapOf(productId to listOf(secondInventoryId to 2)))

                adapter.updateAllocations(reservation, expected)

                val updatedBy: String =
                    databaseClient
                        .sql("SELECT updated_by FROM reservation_allocation WHERE inventory_id = $firstInventoryId")
                        .fetch()
                        .one()
                        .awaitFirst()["updated_by"]
                        .toString()
                assertThat(updatedBy).isNotEqualTo("test")
            }
    }

    @Nested
    inner class `재고 행으로 예약 조회` {
        private suspend fun changeStatus(
            id: Long,
            status: String,
        ) {
            databaseClient
                .sql("UPDATE reservation SET status = :status WHERE reservation_id = :id")
                .bind("status", status)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

        @Test
        fun `주어진 재고 행에 할당된 지정한 상태의 예약을 ID 순서로 한 번씩만 반환한다`() =
            runBlocking<Unit> {
                val first: Long = checkNotNull(adapter.save(reservation("k-1", "ORDER-1")).reservationId)
                val second: Long = checkNotNull(adapter.save(reservation("k-2", "ORDER-2")).reservationId)
                val confirmed: Long = checkNotNull(adapter.save(reservation("k-3", "ORDER-3")).reservationId)
                changeStatus(confirmed, "CONFIRMED")

                assertThat(adapter.findIdsByInventoryIds(setOf(firstInventoryId, secondInventoryId), ReservationStatus.RESERVED))
                    .containsExactly(first, second)
                assertThat(adapter.findIdsByInventoryIds(setOf(firstInventoryId), ReservationStatus.CONFIRMED)).containsExactly(confirmed)
            }

        @Test
        fun `할당이 없는 재고 행과 빈 집합은 결과가 없다`() =
            runBlocking<Unit> {
                adapter.save(reservation())
                val unused: Long =
                    fixture.insertInventory(
                        20L,
                        productId,
                        checkNotNull(lotAdapter.findById(1L)?.lotId ?: fixture.seedLot(productId, "Z").lotId),
                    )

                assertThat(adapter.findIdsByInventoryIds(setOf(unused), ReservationStatus.RESERVED)).isEmpty()
                assertThat(adapter.findIdsByInventoryIds(emptySet(), ReservationStatus.RESERVED)).isEmpty()
            }

        @Test
        fun `다른 상품의 재고 행에 할당된 예약도 그 재고 행으로 찾는다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(reservation()).reservationId)

                assertThat(adapter.findIdsByInventoryIds(setOf(otherInventoryId), ReservationStatus.RESERVED)).containsExactly(id)
            }
    }

    @Nested
    inner class `만료된 예약 조회` {
        private suspend fun saveExpiring(
            orderId: String,
            expiresAt: LocalDateTime,
        ): Long {
            val id: Long = checkNotNull(adapter.save(reservation(key = "key-$orderId", orderId = orderId)).reservationId)
            databaseClient
                .sql("UPDATE reservation SET expires_at = :expiresAt WHERE reservation_id = :id")
                .bind("expiresAt", expiresAt)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            return id
        }

        @Test
        fun `만료 시각이 현재 이하인 확정 전 예약만 만료 시각이 이른 순으로 반환한다`() =
            runBlocking<Unit> {
                val later: Long = saveExpiring("A", now.minusMinutes(1))
                val earlier: Long = saveExpiring("B", now.minusMinutes(10))
                val exact: Long = saveExpiring("C", now)
                saveExpiring("D", now.plusSeconds(1))

                assertThat(adapter.findExpiredIds(now, 10)).containsExactly(earlier, later, exact)
            }

        @Test
        fun `확정, 해제, 만료된 예약은 제외한다`() =
            runBlocking<Unit> {
                val reserved: Long = saveExpiring("A", now.minusMinutes(1))
                listOf("CONFIRMED", "RELEASED", "EXPIRED", "FULFILLED").forEachIndexed { index, status ->
                    val id: Long = saveExpiring("S$index", now.minusMinutes(5))
                    databaseClient
                        .sql(
                            "UPDATE reservation SET status = '$status' WHERE reservation_id = $id",
                        ).fetch()
                        .rowsUpdated()
                        .awaitSingle()
                }

                assertThat(adapter.findExpiredIds(now, 10)).containsExactly(reserved)
            }

        @Test
        fun `한 번에 가져올 개수를 제한한다`() =
            runBlocking<Unit> {
                val ids: List<Long> = (1..5).map { saveExpiring("O$it", now.minusMinutes(10L - it)) }

                assertThat(adapter.findExpiredIds(now, 3)).containsExactlyElementsOf(ids.take(3))
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
        fun `멱등 키를 저장하고 예약과 키로 조회한다`() =
            runBlocking<Unit> {
                val reservationId: Long = checkNotNull(adapter.save(reservation()).reservationId)
                eventAdapter.save(
                    ReservationEvent.create(reservationId, ReservationEventType.REALLOCATED, """{"shortages":[]}""", "wms-realloc-1"),
                )

                val found: ReservationEvent? = eventAdapter.findByIdempotencyKey(reservationId, "wms-realloc-1")

                assertThat(found?.idempotencyKey).isEqualTo("wms-realloc-1")
                assertThat(found?.eventType).isEqualTo(ReservationEventType.REALLOCATED)
                assertThat(eventAdapter.findByIdempotencyKey(reservationId, "other")).isNull()
                assertThat(eventAdapter.findByIdempotencyKey(reservationId + 1, "wms-realloc-1")).isNull()
            }

        @Test
        fun `같은 예약에 같은 멱등 키를 다시 저장하면 동시 변경 예외이고 키가 없는 이력은 여러 개 저장된다`() =
            runBlocking<Unit> {
                val reservationId: Long = checkNotNull(adapter.save(reservation()).reservationId)
                eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.REALLOCATED, null, "wms-realloc-1"))

                assertThrows<ReservationChangedConcurrentlyException> {
                    eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.REALLOCATED, null, "wms-realloc-1"))
                }
                eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.CONFIRMED, null))
                eventAdapter.save(ReservationEvent.create(reservationId, ReservationEventType.RELEASED, null))
                assertThat(eventAdapter.findAllByReservationId(reservationId)).hasSize(3)
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
