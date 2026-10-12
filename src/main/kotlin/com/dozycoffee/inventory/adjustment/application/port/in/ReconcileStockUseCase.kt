package com.dozycoffee.inventory.adjustment.application.port.`in`

import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationRunResult

interface ReconcileStockUseCase {
    /**
     * 재고 행이 있는 모든 창고를 WMS 수량과 대사한다(ADR-0033). 창고마다 실행을 기록하고 불일치한 행마다 처리 대기 보정안을 만든다.
     * 한 창고의 실패(WMS 조회 실패 등)는 그 실행만 실패로 기록하고 다른 창고를 막지 않는다. 같은 날 다시 실행해도 보정안이 중복되지 않는다.
     */
    suspend fun reconcile(): List<ReconciliationRunResult>
}
