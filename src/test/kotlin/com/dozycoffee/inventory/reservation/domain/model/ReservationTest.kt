package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationExpiredException
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class ReservationTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val key: IdempotencyKey = IdempotencyKey.of("svc-oms-order-1")
    private val expiry: ReservationExpiry = ReservationExpiry.create(now.plusMinutes(30), now.plusHours(1), now)

    private fun item(
        productId: Long = 1L,
        quantity: Int = 10,
        vararg allocations: Pair<Long, Int> = arrayOf(5L to 6, 7L to 4),
    ): ReservationItem = ReservationItem.create(productId, quantity, allocations.map { ReservationAllocation.create(it.first, it.second) })

    private fun reservation(
        warehouseId: Long = 10L,
        items: List<ReservationItem> = listOf(item()),
    ): Reservation =
        Reservation.create(
            warehouseId,
            ReservationChannel.of("OMS"),
            ExternalOrderId.of("ORDER-1"),
            expiry,
            key,
            RequesterService.of("svc-oms"),
            items,
        )

    private fun assertRejected(
        code: ReservationErrorCode,
        build: () -> Any,
    ) {
        val e: InvalidDomainValueException = assertThrows { build() }
        assertEquals(code, e.errorCode)
    }

    @Nested
    inner class `생성` {
        @Test
        fun `처음에는 RESERVED이고 만료 시각만 있고 확정 시각은 없다`() {
            val created: Reservation = reservation()

            assertEquals(ReservationStatus.RESERVED, created.status)
            assertEquals(expiry, created.expiry)
            assertEquals("OMS", created.channel.value)
            assertEquals("ORDER-1", created.externalOrderId.value)
            assertEquals("svc-oms", created.requesterService.value)
            assertNull(created.confirmedAt)
            assertNull(created.reservationId)
            assertEquals(1, created.items.size)
        }
    }

    @Nested
    inner class `요청 검증` {
        @Test
        fun `창고 ID는 양수이다`() = assertRejected(ReservationErrorCode.INVALID_RESERVATION_WAREHOUSE) { reservation(warehouseId = 0L) }

        @Test
        fun `상품은 1개 이상 100개 이하이고 중복될 수 없다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { reservation(items = emptyList()) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { reservation(items = listOf(item(1L), item(1L))) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) {
                reservation(items = (1L..101L).map { item(it, 1, 1L to 1) })
            }
            reservation(items = (1L..100L).map { item(it, 1, 1L to 1) })
        }
    }

    @Nested
    inner class `예약 항목` {
        @Test
        fun `할당 수량의 합은 요청 수량과 같아야 한다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(quantity = 10, allocations = arrayOf(1L to 6, 2L to 3)) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(quantity = 10, allocations = arrayOf(1L to 6, 2L to 5)) }
        }

        @Test
        fun `할당이 없거나 같은 재고 행을 두 번 할당할 수 없다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(allocations = emptyArray()) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(quantity = 10, allocations = arrayOf(1L to 5, 1L to 5)) }
        }

        @Test
        fun `상품 ID와 요청 수량은 양수이다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(productId = 0L) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { item(quantity = 0, allocations = arrayOf(1L to 1)) }
        }

        @Test
        fun `할당은 재고 행 ID와 수량이 양수이다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { ReservationAllocation.create(0L, 1) }
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) { ReservationAllocation.create(1L, 0) }
        }

        @Test
        fun `할당 수량의 합은 Int 범위를 넘어도 정확히 비교한다`() {
            assertRejected(ReservationErrorCode.INVALID_RESERVATION_ITEMS) {
                item(quantity = 1, allocations = arrayOf(1L to Int.MAX_VALUE, 2L to Int.MAX_VALUE, 3L to 3))
            }
        }
    }

    @Nested
    inner class `상태 전이` {
        private fun restored(
            status: ReservationStatus,
            expiresAt: LocalDateTime? = now.plusMinutes(30),
            confirmedAt: LocalDateTime? = null,
        ): Reservation =
            Reservation.reconstitute(
                1L,
                10L,
                ReservationChannel.of("OMS"),
                ExternalOrderId.of("ORDER-1"),
                status,
                ReservationExpiry.reconstitute(expiresAt, now.plusHours(1)),
                confirmedAt,
                key,
                RequesterService.of("svc-oms"),
                listOf(item()),
            )

        private fun assertState(block: () -> Any) {
            assertThrows<InvalidReservationStateException> { block() }
        }

        @Nested
        inner class `확정` {
            @Test
            fun `확정 전 예약은 CONFIRMED가 되고 만료 시각이 사라지며 확정 시각이 남는다`() {
                val reservation: Reservation = restored(ReservationStatus.RESERVED)

                assertEquals(true, reservation.confirm(now))

                assertEquals(ReservationStatus.CONFIRMED, reservation.status)
                assertNull(reservation.expiry.expiresAt)
                assertEquals(now.plusHours(1), reservation.expiry.maxExpiresAt)
                assertEquals(now, reservation.confirmedAt)
            }

            @Test
            fun `이미 확정된 예약은 바꾸지 않는다`() {
                val reservation: Reservation = restored(ReservationStatus.CONFIRMED, null, now.minusMinutes(5))

                assertEquals(false, reservation.confirm(now))

                assertEquals(now.minusMinutes(5), reservation.confirmedAt)
            }

            @Test
            fun `만료 시각이 지난 예약은 확정할 수 없다`() {
                assertThrows<ReservationExpiredException> { restored(ReservationStatus.RESERVED, now).confirm(now) }
                assertThrows<ReservationExpiredException> { restored(ReservationStatus.RESERVED, now.minusMinutes(1)).confirm(now) }
            }

            @Test
            fun `해제, 만료, 출고 완료된 예약은 확정할 수 없다`() {
                listOf(ReservationStatus.RELEASED, ReservationStatus.EXPIRED, ReservationStatus.FULFILLED).forEach { status ->
                    assertState { restored(status).confirm(now) }
                }
            }
        }

        @Nested
        inner class `해제` {
            @Test
            fun `확정 전 예약과 확정된 예약은 RELEASED가 되고 만료 시각이 사라진다`() {
                val reserved: Reservation = restored(ReservationStatus.RESERVED)
                val confirmed: Reservation = restored(ReservationStatus.CONFIRMED, null, now)

                assertEquals(true, reserved.release())
                assertEquals(true, confirmed.release())

                assertEquals(ReservationStatus.RELEASED, reserved.status)
                assertNull(reserved.expiry.expiresAt)
                assertEquals(ReservationStatus.RELEASED, confirmed.status)
            }

            @Test
            fun `이미 해제되었거나 만료된 예약은 바꾸지 않는다`() {
                val released: Reservation = restored(ReservationStatus.RELEASED, null)
                val expired: Reservation = restored(ReservationStatus.EXPIRED, now.minusMinutes(1))

                assertEquals(false, released.release())
                assertEquals(false, expired.release())

                assertEquals(ReservationStatus.EXPIRED, expired.status)
                assertEquals(now.minusMinutes(1), expired.expiry.expiresAt)
            }

            @Test
            fun `출고 완료된 예약은 해제할 수 없다`() = assertState { restored(ReservationStatus.FULFILLED, null).release() }
        }

        @Nested
        inner class `연장` {
            @Test
            fun `최대 만료 시각까지 만료 시각을 늘린다`() {
                val reservation: Reservation = restored(ReservationStatus.RESERVED)

                assertEquals(true, reservation.extend(now.plusMinutes(45), now))
                assertEquals(now.plusMinutes(45), reservation.expiry.expiresAt)
                assertEquals(true, reservation.extend(now.plusHours(1), now))
                assertEquals(now.plusHours(1), reservation.expiry.expiresAt)
            }

            @Test
            fun `같은 만료 시각이면 바꾸지 않는다`() {
                val reservation: Reservation = restored(ReservationStatus.RESERVED)

                assertEquals(false, reservation.extend(now.plusMinutes(30), now))
            }

            @Test
            fun `현재 만료 시각보다 이르거나 최대 만료 시각을 넘거나 현재 이전이면 입력 오류다`() {
                val reservation: Reservation = restored(ReservationStatus.RESERVED)

                listOf(now.plusMinutes(29), now.plusHours(1).plusNanos(1000), now, now.minusMinutes(1)).forEach { invalid ->
                    assertRejected(ReservationErrorCode.INVALID_RESERVATION_EXPIRY) { reservation.extend(invalid, now) }
                }
                assertEquals(now.plusMinutes(30), reservation.expiry.expiresAt)
            }

            @Test
            fun `만료 시각이 지난 예약은 연장할 수 없다`() {
                assertThrows<ReservationExpiredException> { restored(ReservationStatus.RESERVED, now).extend(now.plusMinutes(10), now) }
            }

            @Test
            fun `확정 전이 아닌 예약은 연장할 수 없다`() {
                listOf(
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.RELEASED,
                    ReservationStatus.EXPIRED,
                    ReservationStatus.FULFILLED,
                ).forEach { status ->
                    assertState { restored(status).extend(now.plusMinutes(40), now) }
                }
            }
        }

        @Nested
        inner class `만료` {
            @Test
            fun `만료 시각이 지난 확정 전 예약은 EXPIRED가 되고 만료 시각은 남는다`() {
                val reservation: Reservation = restored(ReservationStatus.RESERVED, now.minusMinutes(1))

                assertEquals(true, reservation.expire(now))

                assertEquals(ReservationStatus.EXPIRED, reservation.status)
                assertEquals(now.minusMinutes(1), reservation.expiry.expiresAt)
            }

            @Test
            fun `만료 시각이 현재와 같으면 만료된다`() {
                assertEquals(true, restored(ReservationStatus.RESERVED, now).expire(now))
            }

            @Test
            fun `아직 만료 전이거나 확정 전이 아닌 예약은 바꾸지 않는다`() {
                assertEquals(false, restored(ReservationStatus.RESERVED, now.plusSeconds(1)).expire(now))
                listOf(
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.RELEASED,
                    ReservationStatus.EXPIRED,
                    ReservationStatus.FULFILLED,
                ).forEach { status ->
                    assertEquals(false, restored(status, null).expire(now))
                }
            }
        }
    }

    @Nested
    inner class `동등성` {
        @Test
        fun `저장 전에는 같은 객체만 같고 저장된 뒤에는 식별자가 같으면 같다`() {
            val a: Reservation = reservation()
            val b: Reservation = reservation()
            assertEquals(a, a)
            assertEquals(false, a == b)

            fun restored(id: Long): Reservation =
                Reservation.reconstitute(
                    id,
                    10L,
                    ReservationChannel.of("OMS"),
                    ExternalOrderId.of("O"),
                    ReservationStatus.RESERVED,
                    expiry,
                    null,
                    key,
                    RequesterService.of("svc"),
                    emptyList(),
                )
            assertEquals(restored(1L), restored(1L))
            assertEquals(false, restored(1L) == restored(2L))
        }
    }
}
