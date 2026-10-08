package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class ReservationExpiryTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)

    @Nested
    inner class `생성` {
        @Test
        fun `현재 이후이고 최대 만료 시각 이하이면 만든다`() {
            val expiry: ReservationExpiry = ReservationExpiry.create(now.plusMinutes(30), now.plusHours(1), now)

            assertEquals(now.plusMinutes(30), expiry.expiresAt)
            assertEquals(now.plusHours(1), expiry.maxExpiresAt)
        }

        @Test
        fun `최대 만료 시각과 같아도 되고 현재와 같으면 거부한다`() {
            ReservationExpiry.create(now.plusHours(1), now.plusHours(1), now)

            assertRejected { ReservationExpiry.create(now, now.plusHours(1), now) }
        }

        @Test
        fun `현재 이전이거나 최대 만료 시각을 넘으면 거부한다`() {
            assertRejected { ReservationExpiry.create(now.minusMinutes(1), now.plusHours(1), now) }
            assertRejected { ReservationExpiry.create(now.plusHours(1).plusNanos(1000), now.plusHours(1), now) }
        }

        private fun assertRejected(block: () -> Any) {
            val e: InvalidDomainValueException = assertThrows { block() }
            assertEquals(ReservationErrorCode.INVALID_RESERVATION_EXPIRY, e.errorCode)
        }
    }

    @Nested
    inner class `만료 판정과 변경` {
        private val expiry: ReservationExpiry = ReservationExpiry.create(now.plusMinutes(30), now.plusHours(1), now)

        @Test
        fun `만료 시각이 현재 이하일 때만 만료된 것이다`() {
            assertEquals(false, expiry.isExpiredAt(now.plusMinutes(29)))
            assertEquals(true, expiry.isExpiredAt(now.plusMinutes(30)))
            assertEquals(true, expiry.isExpiredAt(now.plusMinutes(31)))
            assertEquals(false, expiry.cleared().isExpiredAt(now.plusDays(1)))
        }

        @Test
        fun `cleared는 만료 시각만 없애고 최대 만료 시각은 그대로 둔다`() {
            val cleared: ReservationExpiry = expiry.cleared()

            assertNull(cleared.expiresAt)
            assertEquals(now.plusHours(1), cleared.maxExpiresAt)
        }

        @Test
        fun `extendedTo는 현재 만료 시각 이상이고 최대 만료 시각 이하이며 현재 이후일 때만 늘린다`() {
            assertEquals(now.plusMinutes(50), expiry.extendedTo(now.plusMinutes(50), now).expiresAt)
            assertEquals(now.plusHours(1), expiry.extendedTo(now.plusHours(1), now).expiresAt)
            assertEquals(now.plusMinutes(30), expiry.extendedTo(now.plusMinutes(30), now).expiresAt)

            listOf(now.plusMinutes(29), now.plusHours(1).plusNanos(1000)).forEach { invalid ->
                val e: InvalidDomainValueException = assertThrows { expiry.extendedTo(invalid, now) }
                assertEquals(ReservationErrorCode.INVALID_RESERVATION_EXPIRY, e.errorCode)
            }
        }
    }

    @Nested
    inner class `복원` {
        @Test
        fun `확정된 예약은 만료 시각이 없을 수 있다`() {
            val expiry: ReservationExpiry = ReservationExpiry.reconstitute(null, now.plusHours(1))

            assertNull(expiry.expiresAt)
            assertEquals(now.plusHours(1), expiry.maxExpiresAt)
        }
    }
}
