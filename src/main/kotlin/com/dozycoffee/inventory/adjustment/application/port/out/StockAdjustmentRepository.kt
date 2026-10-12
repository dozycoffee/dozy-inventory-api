package com.dozycoffee.inventory.adjustment.application.port.out

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus

/** 조정과 항목을 하나의 묶음으로 저장하고 조회한다. 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface StockAdjustmentRepository {
    /** 새 조정을 항목과 함께 저장한다(항목은 `Lot`, 품질 상태 순서). 같은 멱등 키가 이미 있으면 `DuplicateAdjustmentKeyException`을 던진다 */
    suspend fun save(adjustment: StockAdjustment): StockAdjustment

    suspend fun findById(stockAdjustmentId: Long): StockAdjustment?

    suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): StockAdjustment?

    /** 창고에 처리 대기(`PENDING`) 중인 대사 보정안이 있는 (Lot, 품질 상태)를 반환한다. 대사가 같은 행의 보정안을 중복해서 만들지 않으려고 쓴다 */
    suspend fun findPendingReconciliationTargets(warehouseId: Long): Set<Pair<Long, QualityStatus>>

    /**
     * 상태, 승인자, 승인 시각을 [adjustment]의 값으로 갱신한다. 저장된 상태가 [expectedStatus]일 때만 갱신하고, 그 사이 다른 요청이
     * 상태를 바꿨으면 아무것도 바꾸지 않고 false를 반환한다(조건부 UPDATE). 호출한 서비스의 트랜잭션 안에서 실행한다.
     */
    suspend fun updateState(
        adjustment: StockAdjustment,
        expectedStatus: AdjustmentStatus,
    ): Boolean

    /**
     * 대사 보정안 항목의 inventory·WMS 수량과 변동량을 [item]의 값으로 갱신한다. 보정안이 처리 대기(`PENDING`)일 때만 갱신하고,
     * 그 사이 반영·거부되었으면 아무것도 바꾸지 않고 false를 반환한다.
     */
    suspend fun updateReconciliationItem(item: StockAdjustmentItem): Boolean

    /**
     * 대사 보정안을 최신순(ID 내림차순)으로 [offset]부터 최대 [limit]개 반환한다. [warehouseIds]가 null이면 모든 창고, [status]가 null이면 모든 상태다.
     * 읽기 전용이며 락을 걸지 않는다.
     */
    suspend fun searchReconciliations(
        warehouseIds: Set<Long>?,
        status: AdjustmentStatus?,
        offset: Long,
        limit: Int,
    ): List<StockAdjustment>

    /** [searchReconciliations]의 조건에 맞는 대사 보정안의 개수 */
    suspend fun countReconciliations(
        warehouseIds: Set<Long>?,
        status: AdjustmentStatus?,
    ): Long

    /** 재고에 반영한 뒤 항목별 대상 재고 행을 기록한다. 키는 항목 ID, 값은 재고 행 ID다 */
    suspend fun assignInventoryIds(inventoryIdsByItemId: Map<Long, Long>)
}
