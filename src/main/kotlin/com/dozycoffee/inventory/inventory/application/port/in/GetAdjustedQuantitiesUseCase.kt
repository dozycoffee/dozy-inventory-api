package com.dozycoffee.inventory.inventory.application.port.`in`

interface GetAdjustedQuantitiesUseCase {
    /** 같은 멱등 키의 `ADJUSTMENT` 이력에서 원인 문서(조정 항목) ID별 반영 후 총 수량을 다시 만든다. 조정 재요청이 처음과 같은 결과를 주려고 쓴다 */
    suspend fun getQuantitiesAfter(idempotencyKey: String): Map<Long, Int>
}
