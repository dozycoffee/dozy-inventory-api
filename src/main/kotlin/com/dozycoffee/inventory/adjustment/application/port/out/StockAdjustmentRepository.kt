package com.dozycoffee.inventory.adjustment.application.port.out

import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.global.domain.IdempotencyKey

/** 조정과 항목을 하나의 묶음으로 저장하고 조회한다. 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface StockAdjustmentRepository {
    /** 새 조정을 항목과 함께 저장한다(항목은 `Lot`, 품질 상태 순서). 같은 멱등 키가 이미 있으면 `DuplicateAdjustmentKeyException`을 던진다 */
    suspend fun save(adjustment: StockAdjustment): StockAdjustment

    suspend fun findById(stockAdjustmentId: Long): StockAdjustment?

    suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): StockAdjustment?

    /** 재고에 반영한 뒤 항목별 대상 재고 행을 기록한다. 키는 항목 ID, 값은 재고 행 ID다 */
    suspend fun assignInventoryIds(inventoryIdsByItemId: Map<Long, Long>)
}
