package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult

interface RequestStockAdjustmentUseCase {
    /**
     * WMS가 계산한 실사 조정 변동량을 재고에 반영한다(ADR-0026). 변동량이 카테고리별 승인 임계치를 넘는 항목이 있는데 승인자가 없으면
     * 아무것도 반영하지 않고 거부한다. 항목은 모두 한 트랜잭션에서 반영하며 하나라도 실패하면 전체를 되돌린다.
     * 같은 멱등 키의 재요청은 새로 반영하지 않고 처음과 같은 결과를 반환하고, 같은 키에 다른 내용이 오면 거부한다.
     */
    suspend fun request(command: RequestStockAdjustmentCommand): StockAdjustmentResult
}
