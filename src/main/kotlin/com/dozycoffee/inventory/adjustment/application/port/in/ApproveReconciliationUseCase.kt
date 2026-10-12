package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult

interface ApproveReconciliationUseCase {
    /**
     * 처리 대기 중인 대사 보정안을 승인해 같은 트랜잭션에서 재고에 반영한다(ADR-0033). 승인 시점에 WMS 수량과 지금의 inventory 수량을 다시 확인해 보정안의 값과 다르면 보정안을 새 값으로 고치고(불일치가 사라졌으면 거부 상태로 닫고) 반영하지 않은 채 [com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationChangedException]이다. 임계치를 넘는 보정안은 본사 관리자가 아니면 [com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationAdminRequiredException]이다. 이미 반영된 보정안은 바꾸지 않고 현재 보정안을 돌려준다(멱등). 거부된 보정안은 [com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException]이다. 감소가 가용 수량을 넘으면 반영하지 못하고 보정안은 처리 대기로 남는다.
     * 대사 보정안이 아니거나 없으면 [com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException], 창고가 범위 밖이면
     * [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다.
     */
    suspend fun approve(command: ReviewReconciliationCommand): ReconciliationProposalResult
}
