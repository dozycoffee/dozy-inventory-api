package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/**
 * 관리자 재고 현황 조회 조건. 조건은 모두 선택이고 주어진 조건을 모두 만족하는 재고 행을 준다. [page]는 0부터 시작하고 [size]는 1~[MAX_SIZE]다.
 */
data class SearchInventoriesQuery(
    val warehouseId: Long?,
    val productId: Long?,
    val qualityStatus: QualityStatus?,
    val lotStatus: LotStatus?,
    val allocationHold: Boolean?,
    val page: Int,
    val size: Int,
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
