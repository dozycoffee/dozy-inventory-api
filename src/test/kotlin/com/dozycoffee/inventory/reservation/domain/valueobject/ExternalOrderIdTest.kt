package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExternalOrderIdTest {
    @Test
    fun `비어 있지 않은 100자 이하의 문자열을 받는다`() {
        assertEquals("ORDER-1", ExternalOrderId.of("ORDER-1").value)
        ExternalOrderId.of("O".repeat(100))
    }

    @Test
    fun `null, 빈 값, 공백, 101자는 거부한다`() {
        listOf(null, "", " ", "O".repeat(101)).forEach { value: String? ->
            val e: InvalidDomainValueException = assertThrows { ExternalOrderId.of(value) }
            assertEquals(ReservationErrorCode.INVALID_EXTERNAL_ORDER_ID, e.errorCode)
        }
    }
}
