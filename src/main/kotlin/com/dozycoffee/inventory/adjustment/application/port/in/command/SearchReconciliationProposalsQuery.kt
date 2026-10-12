package com.dozycoffee.inventory.adjustment.application.port.`in`.command

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/**
 * 대사 보정안 목록 조회 조건. [warehouseId]와 [status]는 선택이다. [warehouseId]가 없으면 [scope]의 창고로 제한하고 범위 밖 창고를 지정하면 거부한다.
 * [page]는 0부터 시작하고 [size]는 1~[MAX_SIZE]다.
 */
data class SearchReconciliationProposalsQuery(
    val warehouseId: Long?,
    val status: AdjustmentStatus?,
    val page: Int,
    val size: Int,
    val scope: WarehouseScope,
) {
    init {
        if ((warehouseId != null && warehouseId < 1) || page < 0 || size !in 1..MAX_SIZE) {
            throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION_QUERY)
        }
    }

    companion object {
        const val MAX_SIZE: Int = 100
    }
}
