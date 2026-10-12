package com.dozycoffee.inventory.global.domain

import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WarehouseScopeTest {
    private val only: WarehouseScope = WarehouseScope.Only(setOf(10L, 20L))

    @Nested
    inner class `허용 여부` {
        @Test
        fun `전체 범위는 모든 창고를 허용한다`() {
            assertTrue(WarehouseScope.All.allows(999L))
            WarehouseScope.All.require(999L)
        }

        @Test
        fun `배정된 창고만 허용하고 나머지는 거부한다`() {
            assertTrue(only.allows(10L))
            assertFalse(only.allows(30L))
            only.require(20L)
            assertThrows<WarehouseAccessDeniedException> { only.require(30L) }
        }

        @Test
        fun `빈 범위는 어느 창고도 허용하지 않는다`() {
            assertFalse(WarehouseScope.Only(emptySet()).allows(10L))
        }
    }

    @Nested
    inner class `요청 창고 좁히기` {
        @Test
        fun `창고를 생략하면 전체 범위는 제한 없음(null)이고 배정 범위는 배정된 모든 창고다`() {
            assertNull(WarehouseScope.All.narrow(null))
            assertEquals(setOf(10L, 20L), only.narrow(null))
        }

        @Test
        fun `지정한 창고가 모두 범위 안이면 그대로 돌려준다`() {
            assertEquals(setOf(5L, 6L), WarehouseScope.All.narrow(setOf(5L, 6L)))
            assertEquals(setOf(10L), only.narrow(setOf(10L)))
        }

        @Test
        fun `지정한 창고 중 하나라도 범위 밖이면 거부한다`() {
            assertThrows<WarehouseAccessDeniedException> { only.narrow(setOf(10L, 30L)) }
        }
    }
}
