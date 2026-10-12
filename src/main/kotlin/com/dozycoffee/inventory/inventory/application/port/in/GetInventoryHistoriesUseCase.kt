package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult

interface GetInventoryHistoriesUseCase {
    /**
     * 재고 행의 수량 변동 이력을 최신순으로 페이지를 나눠 준다. [page]는 0부터 시작하고 [size]는 1~100이다.
     * 재고 행이 없으면 [com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException]이다.
     */
    suspend fun getHistories(
        inventoryId: Long,
        page: Int,
        size: Int,
    ): PageResult<InventoryHistoryResult>
}
