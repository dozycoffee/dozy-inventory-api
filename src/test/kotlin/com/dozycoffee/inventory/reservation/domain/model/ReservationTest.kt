package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
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
