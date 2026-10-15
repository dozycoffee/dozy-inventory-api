package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventorySummariesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventorySummaryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

interface SearchInventorySummariesUseCase {
    /** 관리자 상품별 재고 요약. 조건에 맞는 재고 행을 상품 단위로 합쳐 `상품 ID` 순서로 페이지를 나눠 준다. 조회는 약속이 아니다 */
    suspend fun searchSummaries(query: SearchInventorySummariesQuery): PageResult<InventorySummaryResult>
}
