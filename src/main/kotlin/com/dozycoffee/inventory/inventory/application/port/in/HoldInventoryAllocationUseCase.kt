package com.dozycoffee.inventory.inventory.application.port.`in`

interface HoldInventoryAllocationUseCase {
    /**
     * 재고 행을 신규 할당에서 제외한다(ADR-0031). 이미 보류 중인 행은 처음의 사유와 시각을 유지한다. 이미 잡힌 예약 수량은 그대로다.
     * 보류는 실사 조정이나 대사 보정이 그 행을 정리하면 풀린다. 호출한 서비스의 트랜잭션 안에서 실행한다.
     */
    suspend fun hold(
        inventoryIds: Set<Long>,
        reason: String,
    )
}
