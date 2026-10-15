package com.dozycoffee.inventory.product.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.product.domain.exception.ProductErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ListProductsQueryTest {
    @Test
    fun `오프셋은 페이지 곱하기 크기이다`() {
        assertEquals(40L, ListProductsQuery(null, null, null, null, page = 2, size = 20).offset)
    }

    @Test
    fun `크기 경계값 1과 100은 허용한다`() {
        ListProductsQuery(null, null, null, null, page = 0, size = 1)
        ListProductsQuery(null, null, null, null, page = 0, size = 100)
    }

    @Test
    fun `페이지가 음수이거나 크기가 범위를 벗어나면 실패`() {
        listOf(-1 to 10, 0 to 0, 0 to 101).forEach { (page: Int, size: Int) ->
            val e: InvalidDomainValueException = assertThrows { ListProductsQuery(null, null, null, null, page, size) }
            assertEquals(ProductErrorCode.INVALID_PAGE_REQUEST, e.errorCode)
        }
    }

    @Test
    fun `검색어는 앞뒤 공백을 뺀 길이가 100자까지 허용한다`() {
        ListProductsQuery(null, "a".repeat(100), null, null, page = 0, size = 20)
        ListProductsQuery(null, " ${"a".repeat(100)} ", null, null, page = 0, size = 20)
        ListProductsQuery(null, "   ", null, null, page = 0, size = 20)
    }

    @Test
    fun `검색어가 100자를 넘으면 실패`() {
        val e: InvalidDomainValueException = assertThrows { ListProductsQuery(null, "a".repeat(101), null, null, page = 0, size = 20) }
        assertEquals(ProductErrorCode.INVALID_KEYWORD, e.errorCode)
    }
}
