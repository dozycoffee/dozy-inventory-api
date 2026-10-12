package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult

interface GetWarehouseQuantitiesUseCase {
    /**
     * 창고의 재고 행별 총 수량을 `inventory_id` 순서로 모두 반환한다(수량 0인 행 포함). [productId]와 [lotNumber]를 주면 그 조건의 행만 준다.
     * 대사가 WMS 수량과 비교하는 데 쓰는 읽기 전용 조회이며 락을 걸지 않는다(ADR-0033).
     */
    suspend fun getQuantities(
        warehouseId: Long,
        productId: Long? = null,
        lotNumber: String? = null,
    ): List<WarehouseQuantityResult>
}
