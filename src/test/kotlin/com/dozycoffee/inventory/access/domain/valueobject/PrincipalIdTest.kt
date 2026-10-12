package com.dozycoffee.inventory.access.domain.valueobject

import com.dozycoffee.inventory.access.domain.exception.AccessErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class PrincipalIdTest {
    @Test
    fun `UUID 문자열은 소문자로 정규화한다`() {
        assertEquals("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73", PrincipalId.of("0199A3C2-8E5A-7F30-8C4B-9D1E2F6A0B73").value)
    }

    @Test
    fun `UUID 객체에서 만든 값과 문자열에서 만든 값이 같다`() {
        val uuid: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
        assertEquals(PrincipalId.of(uuid), PrincipalId.of(uuid.toString()))
    }

    @Test
    fun `UUID 형식이 아니면 거부한다`() {
        listOf(
            null,
            "",
            " ",
            "abc",
            "1-1-1-1-1",
            "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b7",
            "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b7g",
            " 0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
        ).forEach { invalid ->
            val e: InvalidDomainValueException = assertThrows { PrincipalId.of(invalid) }
            assertEquals(AccessErrorCode.INVALID_PRINCIPAL_ID, e.errorCode)
        }
    }
}
