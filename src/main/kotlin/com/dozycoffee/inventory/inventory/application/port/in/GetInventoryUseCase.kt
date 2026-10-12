package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult

interface GetInventoryUseCase {
    /**
     * 재고 행 하나의 상세(Lot, 수량, 예약, 보류). 없으면 [com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException],
     * 행의 창고가 [scope] 밖이면 [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다.
     */
    suspend fun getById(
        inventoryId: Long,
        scope: WarehouseScope,
    ): InventoryResult
}
