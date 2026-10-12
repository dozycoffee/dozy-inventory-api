package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.global.domain.WarehouseScope

interface GetReconciliationProposalUseCase {
    /**
     * 대사 보정안 하나. 대사 보정안이 아니거나 없으면 [com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException],
     * 창고가 [scope] 밖이면 [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다.
     */
    suspend fun getById(
        stockAdjustmentId: Long,
        scope: WarehouseScope,
    ): ReconciliationProposalResult
}
