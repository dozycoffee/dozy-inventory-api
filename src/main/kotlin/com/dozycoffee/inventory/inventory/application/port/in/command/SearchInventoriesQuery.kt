package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/**
 * 관리자 재고 현황 조회 조건. 조건은 모두 선택이고 주어진 조건을 모두 만족하는 재고 행을 준다. [page]는 0부터 시작하고 [size]는 1~[MAX_SIZE]다.
 * [scope]는 요청자가 접근할 수 있는 창고이며, [warehouseId]가 없으면 그 범위의 창고로 제한하고 범위 밖의 창고를 지정하면 거부한다(ADR-0032).
 */
data class SearchInventoriesQuery(
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
        if ((warehouseId != null && warehouseId < 1) || (productId != null && productId < 1) || page < 0 || size !in 1..MAX_SIZE) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_INVENTORY_QUERY)
        }
    }

    companion object {
        const val MAX_SIZE: Int = 100
    }
}
