package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ReturnResult

interface ConfirmReturnUseCase {
    /**
     * WMS가 검수한 반품 한 품목을 재고에 되돌리고 `RETURN` 이력과 재고 증가 이벤트를 남긴다(ADR-0029).
     * 등록된 Lot만 받고 품질 상태는 정상 또는 불량이다. 같은 멱등 키의 재요청은 반영하지 않고 이전 결과를 반환한다.
     */
    suspend fun confirm(command: ConfirmReturnCommand): ReturnResult
}
