package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/**
 * 조정 항목 하나. `Lot × 품질 상태`의 변동량(+/−)이고 0일 수 없다. [inventoryId]는 재고에 반영한 뒤에 정해지는 대상 재고 행이다
 * (행이 아직 없던 증가는 반영하면서 만들어진다).
 */
class StockAdjustmentItem private constructor(
    val stockAdjustmentItemId: Long?,
    val inventoryId: Long?,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    val quantityChange: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StockAdjustmentItem) return false
        return stockAdjustmentItemId != null && stockAdjustmentItemId == other.stockAdjustmentItemId
    }

    override fun hashCode(): Int = stockAdjustmentItemId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            lotId: Long,
            qualityStatus: QualityStatus,
            quantityChange: Int,
        ): StockAdjustmentItem {
            if (lotId < 1 || quantityChange == 0) throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT)
            return StockAdjustmentItem(null, null, lotId, qualityStatus, quantityChange)
        }

        fun reconstitute(
            stockAdjustmentItemId: Long,
            inventoryId: Long?,
            lotId: Long,
            qualityStatus: QualityStatus,
            quantityChange: Int,
        ): StockAdjustmentItem = StockAdjustmentItem(stockAdjustmentItemId, inventoryId, lotId, qualityStatus, quantityChange)
    }
}
