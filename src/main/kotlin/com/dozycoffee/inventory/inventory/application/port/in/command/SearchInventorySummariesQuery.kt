package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/**
 * 상품별 재고 요약 조회 조건. 재고 현황([SearchInventoriesQuery])과 같은 조건으로 재고 행을 거른 뒤 상품 단위로 합친다.
 * [page]는 0부터 시작하고 [size]는 1~[SearchInventoriesQuery.MAX_SIZE]이며 한 줄이 상품 하나다. [scope]의 의미는 재고 현황과 같다(ADR-0032).
 */
data class SearchInventorySummariesQuery(
    val warehouseId: Long?,
    val productId: Long?,
    val qualityStatus: QualityStatus?,
    val lotStatus: LotStatus?,
    val allocationHold: Boolean?,
    val page: Int,
    val size: Int,
    val scope: WarehouseScope,
) {
    init {
        if ((warehouseId != null && warehouseId < 1) ||
            (productId != null && productId < 1) ||
            page < 0 ||
            size !in 1..SearchInventoriesQuery.MAX_SIZE
        ) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_INVENTORY_QUERY)
        }
    }
}
