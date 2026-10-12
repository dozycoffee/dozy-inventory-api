package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult

interface RejectReconciliationUseCase {
    /**
     * 처리 대기 중인 대사 보정안을 거부한다. 임계치와 무관하게 범위 안의 담당자가 할 수 있다. 이미 거부된 보정안은 바꾸지 않고 현재 보정안을 돌려주고(멱등), 반영된 보정안은 [com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException]이다.
     * 대사 보정안이 아니거나 없으면 [com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException], 창고가 범위 밖이면
     * [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다.
     */
    suspend fun reject(command: ReviewReconciliationCommand): ReconciliationProposalResult
}
