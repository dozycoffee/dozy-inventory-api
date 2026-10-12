package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmDisposalCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.DisposalResult

interface ConfirmDisposalUseCase {
    /**
     * WMS가 확정한 폐기 한 품목을 재고에서 차감하고 `DISPOSAL` 이력과 재고 감소 이벤트를 남긴다(ADR-0028).
     * 불량 또는 폐기 예정 재고만 폐기할 수 있고 정상 품질 재고는 거부한다. 같은 멱등 키의 재요청은 반영하지 않고 이전 결과를 반환한다.
     */
    suspend fun confirm(command: ConfirmDisposalCommand): DisposalResult
}
