package com.dozycoffee.inventory.inventory.application.port.`in`

interface GetOutboundResultUseCase {
    /** 같은 멱등 키와 원인 문서의 `OUTBOUND` 이력에서 재고 행별 처리 후 총 수량을 다시 만든다. 출고 재요청이 처음과 같은 결과를 주려고 쓴다 */
    suspend fun getQuantitiesAfter(
        idempotencyKey: String,
        referenceId: Long,
    ): Map<Long, Int>
}
