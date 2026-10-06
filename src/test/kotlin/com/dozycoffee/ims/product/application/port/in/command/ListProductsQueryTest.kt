package com.dozycoffee.ims.product.application.port.`in`.command

import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.product.domain.exception.ProductErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ListProductsQueryTest {
    @Test
    fun `오프셋은 페이지 곱하기 크기이다`() {
        assertEquals(40L, ListProductsQuery(null, null, null, page = 2, size = 20).offset)
    }

    @Test
    fun `크기 경계값 1과 100은 허용한다`() {
        ListProductsQuery(null, null, null, page = 0, size = 1)
        ListProductsQuery(null, null, null, page = 0, size = 100)
    }

    @Test
    fun `페이지가 음수이거나 크기가 범위를 벗어나면 실패`() {
        listOf(-1 to 10, 0 to 0, 0 to 101).forEach { (page: Int, size: Int) ->
            val e: InvalidDomainValueException = assertThrows { ListProductsQuery(null, null, null, page, size) }
            assertEquals(ProductErrorCode.INVALID_PAGE_REQUEST, e.errorCode)
        }
    }
}
