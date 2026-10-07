package com.dozycoffee.ims.inventory.domain.valueobject

import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.inventory.domain.exception.InventoryErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdempotencyKeyTest {
    @Test
    fun `UUID와 서비스 문서 ID 형태의 키를 허용하고 대소문자를 구분한다`() {
        assertEquals("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73", IdempotencyKey.of("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73").value)
        assertEquals("svc-wms-inbound-123", IdempotencyKey.of("svc-wms-inbound-123").value)
        assertNotEquals(IdempotencyKey.of("abc-1"), IdempotencyKey.of("ABC-1"))
    }

    @Test
    fun `100자는 허용하고 101자는 거부한다`() {
        IdempotencyKey.of("a".repeat(100))

        assertThrows<InvalidDomainValueException> { IdempotencyKey.of("a".repeat(101)) }
    }

    @Test
    fun `null, 빈 값, 공백, 제어 문자, 비ASCII 문자는 거부한다`() {
        listOf(null, "", "  ", "a b", "key\n", "키-1", "é").forEach { value: String? ->
            val e: InvalidDomainValueException = assertThrows { IdempotencyKey.of(value) }
            assertEquals(InventoryErrorCode.INVALID_IDEMPOTENCY_KEY, e.errorCode)
        }
    }
}
