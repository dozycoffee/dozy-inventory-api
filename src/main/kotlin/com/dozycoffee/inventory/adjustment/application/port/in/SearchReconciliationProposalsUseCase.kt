package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.SearchReconciliationProposalsQuery
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

interface SearchReconciliationProposalsUseCase {
    /** 대사 보정안을 최신순으로 페이지를 나눠 준다. 창고가 범위 밖이면 [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다 */
    suspend fun search(query: SearchReconciliationProposalsQuery): PageResult<ReconciliationProposalResult>
}
