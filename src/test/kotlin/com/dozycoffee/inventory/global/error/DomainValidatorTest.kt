package com.dozycoffee.inventory.global.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DomainValidatorTest {
    @Test
    fun `null이 아니면 값을 그대로 반환한다`() {
        assertEquals(1, DomainValidator.requireNonNull(1, CommonErrorCode.VALIDATION_FAILED))
    }

    @Test
    fun `null이면 전달한 오류 코드로 예외를 던진다`() {
        val e: InvalidDomainValueException =
            assertThrows { DomainValidator.requireNonNull<Int>(null, CommonErrorCode.VALIDATION_FAILED) }

        assertEquals(CommonErrorCode.VALIDATION_FAILED, e.errorCode)
    }

    @Test
    fun `공백이 아닌 문자열은 그대로 반환한다`() {
        assertEquals(" a ", DomainValidator.requireNotBlank(" a ", CommonErrorCode.VALIDATION_FAILED))
    }

    @Test
    fun `null, 빈 문자열, 공백이면 전달한 오류 코드로 예외를 던진다`() {
        listOf(null, "", "  ").forEach { value: String? ->
            val e: InvalidDomainValueException =
                assertThrows { DomainValidator.requireNotBlank(value, CommonErrorCode.VALIDATION_FAILED) }

            assertEquals(CommonErrorCode.VALIDATION_FAILED, e.errorCode)
        }
    }
}
