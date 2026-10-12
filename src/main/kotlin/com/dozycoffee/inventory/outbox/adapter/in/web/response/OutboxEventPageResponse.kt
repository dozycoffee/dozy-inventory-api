package com.dozycoffee.inventory.outbox.adapter.`in`.web.response

import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult

/** 격리 이벤트 페이지 응답. [page]는 0부터 시작하고 [totalPages]는 전체 항목을 [size]개씩 나눈 페이지 수다 */
data class OutboxEventPageResponse(
    val items: List<OutboxEventResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(result: PageResult<OutboxEventResult>): OutboxEventPageResponse =
            OutboxEventPageResponse(
                result.items.map(OutboxEventResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            )
    }
}
