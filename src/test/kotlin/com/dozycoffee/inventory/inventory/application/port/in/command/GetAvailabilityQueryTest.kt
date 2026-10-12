package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetAvailabilityQueryTest {
    private fun assertInvalid(block: () -> Unit) {
        val e: InvalidDomainValueException = assertThrows { block() }
        assertEquals(InventoryErrorCode.INVALID_AVAILABILITY_QUERY, e.errorCode)
    }

    @Nested
    inner class `상품` {
        @Test
        fun `1개 이상 100개 이하만 허용한다`() {
            GetAvailabilityQuery((1L..100L).toSet(), null, WarehouseScope.All)

            assertInvalid { GetAvailabilityQuery(emptySet(), null, WarehouseScope.All) }
            assertInvalid { GetAvailabilityQuery((1L..101L).toSet(), null, WarehouseScope.All) }
        }

        @Test
        fun `양수 ID만 허용한다`() {
            assertInvalid { GetAvailabilityQuery(setOf(0L), null, WarehouseScope.All) }
            assertInvalid { GetAvailabilityQuery(setOf(1L, -1L), null, WarehouseScope.All) }
        }
    }

    @Nested
    inner class `창고` {
        @Test
        fun `생략하면 전체 창고이고 지정하면 1개 이상 100개 이하만 허용한다`() {
            GetAvailabilityQuery(setOf(1L), null, WarehouseScope.All)
            GetAvailabilityQuery(setOf(1L), (1L..100L).toSet(), WarehouseScope.All)

            assertInvalid { GetAvailabilityQuery(setOf(1L), emptySet(), WarehouseScope.All) }
            assertInvalid { GetAvailabilityQuery(setOf(1L), (1L..101L).toSet(), WarehouseScope.All) }
        }

        @Test
        fun `양수 ID만 허용한다`() {
            assertInvalid { GetAvailabilityQuery(setOf(1L), setOf(0L), WarehouseScope.All) }
        }
    }
}
