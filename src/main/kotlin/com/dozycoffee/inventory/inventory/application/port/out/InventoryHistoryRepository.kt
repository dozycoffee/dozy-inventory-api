package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory

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

    /** 재고 행의 이력을 최신순(`created_at`, ID 내림차순)으로 [offset]부터 최대 [limit]개 반환한다 */
    suspend fun findPageByInventoryId(
        inventoryId: Long,
        offset: Long,
        limit: Int,
    ): List<InventoryHistory>

    suspend fun countByInventoryId(inventoryId: Long): Long
}
