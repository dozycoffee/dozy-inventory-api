package com.dozycoffee.inventory.access.domain.model

import com.dozycoffee.inventory.access.domain.exception.AccessErrorCode
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WarehouseAccessTest {
    private val principal: PrincipalId = PrincipalId.of("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")

    private fun assertRejected(warehouseIds: List<Long>) {
        val e: InvalidDomainValueException = assertThrows { WarehouseAccess.of(principal, warehouseIds) }
        assertEquals(AccessErrorCode.INVALID_WAREHOUSE_ACCESS, e.errorCode)
    }

    @Test
    fun `창고 집합을 만들고 빈 목록은 소속 없음이다`() {
        assertEquals(setOf(10L, 20L), WarehouseAccess.of(principal, listOf(20L, 10L)).warehouseIds)
        assertEquals(emptySet<Long>(), WarehouseAccess.of(principal, emptyList()).warehouseIds)
    }

    @Test
    fun `양수가 아니거나 중복이거나 1000개를 넘으면 거부한다`() {
        assertRejected(listOf(0L))
        assertRejected(listOf(-1L, 2L))
        assertRejected(listOf(10L, 10L))
        assertRejected((1L..1001L).toList())
        assertEquals(1000, WarehouseAccess.of(principal, (1L..1000L).toList()).warehouseIds.size)
    }

    @Test
    fun `같은 직원이면 같다`() {
        assertEquals(WarehouseAccess.of(principal, listOf(1L)), WarehouseAccess.of(principal, listOf(2L)))
    }
}
