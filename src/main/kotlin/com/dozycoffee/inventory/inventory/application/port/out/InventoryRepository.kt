package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import java.time.LocalDateTime

/**
 * 재고 행의 조회와 원자적 수량 갱신. 수량을 바꾸는 메서드는 읽고-계산하고-쓰지 않고 DB의 조건부 UPDATE 한 문장으로 갱신한 뒤
 * 갱신된 행을 반환한다. 조건을 만족하지 못하면 원인에 맞는 도메인 예외를 던지고 아무것도 바꾸지 않는다
 * (행 없음 [com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException], 그 외는 `Inventory`의 규칙 위반 예외).
 * 호출한 서비스의 트랜잭션 안에서 실행한다.
 */
interface InventoryRepository {
    suspend fun findById(inventoryId: Long): Inventory?

    suspend fun findByKey(key: InventoryKey): Inventory?

    /**
     * 상품별, 창고별 가용 수량. [warehouseIds]가 null이면 해당 상품의 재고 행이 있는 모든 창고를 포함하고,
     * 주어지면 그 창고의 행만 센다. 재고 행이 없는 (창고, 상품) 조합은 결과에 없다.
     */
    suspend fun findAvailability(
        productIds: Set<Long>,
        warehouseIds: Set<Long>?,
    ): List<AvailabilityRow>

    /** 같은 키(창고 × Lot × 품질 상태)의 행이 있으면 총 수량을 더하고 없으면 수량이 [amount]인 행을 만든다 */
    suspend fun increase(
        key: InventoryKey,
        productId: Long,
        amount: Int,
    ): Inventory

    /** 가용 수량 안에서 총 수량을 줄인다 */
    suspend fun decrease(
        inventoryId: Long,
        amount: Int,
    ): Inventory

    /** 정상 품질이고 할당 보류가 아닌 행의 가용 수량을 예약한다 */
    suspend fun reserve(
        inventoryId: Long,
        amount: Int,
    ): Inventory

    suspend fun release(
        inventoryId: Long,
        amount: Int,
    ): Inventory

    /** 예약된 수량의 총 수량과 예약 수량을 함께 줄인다 */
    suspend fun ship(
        inventoryId: Long,
        amount: Int,
    ): Inventory

    /** 신규 할당에서 제외한다. 이미 보류 중이면 처음의 사유와 시각을 유지한다 */
    suspend fun holdAllocation(
        inventoryId: Long,
        reason: String?,
        at: LocalDateTime,
    ): Inventory

    suspend fun releaseAllocationHold(inventoryId: Long): Inventory
}
