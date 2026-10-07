package com.dozycoffee.inventory.global.domain

import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RequesterServiceTest {
    @Test
    fun `서비스 이름과 principalId 형태를 허용하고 50자까지 받는다`() {
        assertEquals("svc-wms", RequesterService.of("svc-wms").value)
        assertEquals("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73", RequesterService.of("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73").value)
        RequesterService.of("a".repeat(50))
    }

    @Test
    fun `null, 빈 값, 공백, 51자는 거부한다`() {
        listOf(null, "", "  ", "a".repeat(51)).forEach { value: String? ->
            val e: InvalidDomainValueException = assertThrows { RequesterService.of(value) }
            assertEquals(CommonErrorCode.INVALID_REQUESTER_SERVICE, e.errorCode)
        }
    }
}
