package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

interface SearchInventoriesUseCase {
    /** 관리자 재고 현황. 재고 행(창고 × Lot × 품질 상태) 단위이고 `창고, 상품, 유통기한, ID` 순서로 페이지를 나눠 준다. 조회는 약속이 아니다 */
    suspend fun search(query: SearchInventoriesQuery): PageResult<InventoryResult>
}
