package com.dozycoffee.inventory.inventory.domain.model

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.AllocationHeldException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotReservableException
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import java.time.LocalDateTime

/**
 * 창고 × Lot × 품질 상태 단위 재고 한 행. 총 수량(quantity), 예약 수량(reservedQuantity), 가용 수량(총 − 예약)을 가진다.
 *
 * 불변식: 총 수량 ≥ 0, 0 ≤ 예약 수량 ≤ 총 수량, 할당 보류이면 보류 시각이 있다. 수량이 0이 되어도 행은 유지한다(삭제 연산이 없다).
 * 동시 요청의 최종 판정은 DB의 조건부 UPDATE와 CHECK 제약이 하고, 이 모델은 같은 규칙을 표현하며 실패 원인을 알려 준다.
 */
class Inventory private constructor(
    val inventoryId: Long?,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    quantity: Int,
    reservedQuantity: Int,
    allocationHold: Boolean,
    holdReason: String?,
    heldAt: LocalDateTime?,
) {
    var quantity: Int = quantity
        private set

    var reservedQuantity: Int = reservedQuantity
        private set

    var allocationHold: Boolean = allocationHold
        private set

    var holdReason: String? = holdReason
        private set

    var heldAt: LocalDateTime? = heldAt
        private set

    val key: InventoryKey
        get() = InventoryKey(warehouseId, lotId, qualityStatus)

    val availableQuantity: Int
        get() = quantity - reservedQuantity

    /** 가용 재고에 포함되는 행인지. 정상 품질이고 할당 보류가 아니어야 한다 */
    val isAllocatable: Boolean
        get() = qualityStatus == QualityStatus.NORMAL && !allocationHold

    /** 총 수량을 늘린다(입고, 반품 복귀, 조정 증가) */
    fun increase(amount: Int) {
        requirePositive(amount)
        if (quantity.toLong() + amount > Int.MAX_VALUE) throw InvalidDomainValueException(InventoryErrorCode.QUANTITY_OVERFLOW)
        quantity += amount
    }

    /** 가용 수량 안에서 총 수량을 줄인다(폐기, 조정 감소). 예약된 수량은 줄일 수 없다 */
    fun decrease(amount: Int) {
        requirePositive(amount)
        if (availableQuantity < amount) throw InsufficientAvailableQuantityException()
        quantity -= amount
    }

    /** 가용 수량을 예약한다. 정상 품질이고 할당 보류가 아닌 행만 예약할 수 있다 */
    fun reserve(amount: Int) {
        checkReservable(amount)
        reservedQuantity += amount
    }

    /** 예약 가능 여부를 검사하고 불가하면 원인에 맞는 예외를 던진다. 조건부 UPDATE가 0건일 때 원인 판별에도 쓴다 */
    fun checkReservable(amount: Int) {
        requirePositive(amount)
        if (qualityStatus != QualityStatus.NORMAL) throw InventoryNotReservableException()
        if (allocationHold) throw AllocationHeldException()
        if (availableQuantity < amount) throw InsufficientAvailableQuantityException()
    }

    /** 예약 수량을 줄여 가용 수량으로 되돌린다(예약 해제, 만료). 품질 상태나 보류와 무관하다 */
    fun release(amount: Int) {
        requirePositive(amount)
        if (reservedQuantity < amount) throw InsufficientReservedQuantityException()
        reservedQuantity -= amount
    }

    /** 예약된 수량을 출고 확정한다. 총 수량과 예약 수량을 함께 줄인다 */
    fun ship(amount: Int) {
        requirePositive(amount)
        if (reservedQuantity < amount) throw InsufficientReservedQuantityException()
        quantity -= amount
        reservedQuantity -= amount
    }

    /** 신규 할당에서 제외한다. 이미 보류 중이면 처음의 사유와 시각을 유지하고 false를 반환한다 */
    fun hold(
        reason: String?,
        at: LocalDateTime,
    ): Boolean {
        val validReason: String = requireValidHoldReason(reason)
        if (allocationHold) return false
        allocationHold = true
        holdReason = validReason
        heldAt = at
        return true
    }

    /** 보류를 푼다. 보류 중이 아니었으면 false를 반환한다 */
    fun releaseHold(): Boolean {
        if (!allocationHold) return false
        allocationHold = false
        holdReason = null
        heldAt = null
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Inventory) return false
        return inventoryId != null && inventoryId == other.inventoryId
    }

    override fun hashCode(): Int = inventoryId?.hashCode() ?: System.identityHashCode(this)

    private fun requirePositive(amount: Int) {
        requireValidAmount(amount)
    }

    companion object {
        private const val MAX_HOLD_REASON_LENGTH: Int = 100

        /** 수량 변경 요청의 수량은 1 이상이어야 한다. 모델 없이 SQL로 갱신하는 경로도 같은 규칙을 쓴다 */
        fun requireValidAmount(amount: Int) {
            if (amount < 1) throw InvalidDomainValueException(InventoryErrorCode.INVALID_QUANTITY)
        }

        /** 보류 사유는 비어 있을 수 없고 100자를 넘을 수 없다 */
        fun requireValidHoldReason(reason: String?): String {
            if (reason.isNullOrBlank() || reason.length > MAX_HOLD_REASON_LENGTH) {
                throw InvalidDomainValueException(InventoryErrorCode.INVALID_HOLD_REASON)
            }
            return reason
        }

        /** 수량이 0인 새 행을 만든다. 입고는 이 행의 수량을 늘리는 것이다 */
        fun create(
            warehouseId: Long,
            productId: Long,
            lotId: Long,
            qualityStatus: QualityStatus,
        ): Inventory = Inventory(null, warehouseId, productId, lotId, qualityStatus, 0, 0, false, null, null)

        fun reconstitute(
            inventoryId: Long,
            warehouseId: Long,
            productId: Long,
            lotId: Long,
            qualityStatus: QualityStatus,
            quantity: Int,
            reservedQuantity: Int,
            allocationHold: Boolean,
            holdReason: String?,
            heldAt: LocalDateTime?,
        ): Inventory =
            Inventory(
                inventoryId,
                warehouseId,
                productId,
                lotId,
                qualityStatus,
                quantity,
                reservedQuantity,
                allocationHold,
                holdReason,
                heldAt,
            )
    }
}
