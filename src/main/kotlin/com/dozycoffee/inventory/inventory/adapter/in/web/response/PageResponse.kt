package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

/** 페이지 응답. [page]는 0부터 시작하고 [totalPages]는 전체 항목을 [size]개씩 나눈 페이지 수다 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <S, T> from(
            result: PageResult<S>,
            transform: (S) -> T,
        ): PageResponse<T> = PageResponse(result.items.map(transform), result.page, result.size, result.totalElements, result.totalPages)
    }
}
