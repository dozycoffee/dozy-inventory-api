package com.dozycoffee.inventory.inventory.application.port.`in`.result

/** 페이지 조회 결과. [page]는 0부터 시작한다 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    val totalPages: Int
        get() = if (totalElements == 0L) 0 else ((totalElements + size - 1) / size).toInt()
}
