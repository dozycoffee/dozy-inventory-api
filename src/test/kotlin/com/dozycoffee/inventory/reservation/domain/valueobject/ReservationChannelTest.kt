package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReservationChannelTest {
    @Test
    fun `비어 있지 않은 50자 이하의 문자열을 받는다`() {
        assertEquals("OMS", ReservationChannel.of("OMS").value)
        ReservationChannel.of("A".repeat(50))
    }

    @Test
    fun `null, 빈 값, 공백, 51자는 거부한다`() {
        listOf(null, "", " ", "A".repeat(51)).forEach { value: String? ->
            val e: InvalidDomainValueException = assertThrows { ReservationChannel.of(value) }
            assertEquals(ReservationErrorCode.INVALID_RESERVATION_CHANNEL, e.errorCode)
        }
    }
}
