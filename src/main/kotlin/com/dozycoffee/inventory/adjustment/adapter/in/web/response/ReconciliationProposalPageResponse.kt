package com.dozycoffee.inventory.adjustment.adapter.`in`.web.response

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

/** 보정안 페이지 응답. [page]는 0부터 시작하고 [totalPages]는 전체 항목을 [size]개씩 나눈 페이지 수다 */
data class ReconciliationProposalPageResponse(
    val items: List<ReconciliationProposalResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(result: PageResult<ReconciliationProposalResult>): ReconciliationProposalPageResponse =
            ReconciliationProposalPageResponse(
                result.items.map(ReconciliationProposalResponse::from),
                result.page,
                result.size,
                result.totalElements,
                result.totalPages,
            )
    }
}
