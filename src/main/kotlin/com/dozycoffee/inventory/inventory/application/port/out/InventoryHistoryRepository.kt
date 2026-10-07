package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.valueobject.IdempotencyKey

interface InventoryHistoryRepository {
    /**
     * 이력을 저장한다. 같은 (멱등 키, 재고 행)의 이력이 이미 있으면
     * [com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException]을 던지고, 호출한 트랜잭션의 수량 갱신도 함께 롤백된다.
     */
    suspend fun save(history: InventoryHistory): InventoryHistory

    suspend fun findByIdempotencyKey(
        idempotencyKey: IdempotencyKey,
        inventoryId: Long,
    ): InventoryHistory?

    /** 한 요청이 여러 재고 행을 바꾼 경우의 이력 전체. 재고 행 식별자 오름차순이다 */
    suspend fun findAllByIdempotencyKey(idempotencyKey: IdempotencyKey): List<InventoryHistory>
}
