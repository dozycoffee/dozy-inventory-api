package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult

interface ConfirmInboundUseCase {
    /** WMS가 확정한 입고 한 품목을 재고에 반영한다. 같은 멱등 키의 재요청은 반영하지 않고 이전 결과를 반환한다 */
    suspend fun confirm(command: ConfirmInboundCommand): InboundResult
}
