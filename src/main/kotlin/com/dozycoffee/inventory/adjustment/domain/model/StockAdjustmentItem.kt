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
    quantityChange: Int,
    inventoryQuantity: Int?,
    wmsQuantity: Int?,
) {
    var quantityChange: Int = quantityChange
        private set

    /** 대사 보정안을 만든 시점의 inventory 총 수량. 실사 조정 항목에는 없다 */
    var inventoryQuantity: Int? = inventoryQuantity
        private set

    /** 대사 보정안을 만든 시점의 WMS 수량. 실사 조정 항목에는 없다 */
    var wmsQuantity: Int? = wmsQuantity
        private set

    /** 대사 보정안의 두 수량을 새 값으로 바꾸고 변동량(WMS − inventory)을 다시 계산한다. 두 수량이 같으면 바꿀 수 없다 */
    fun revise(
        inventoryQuantity: Int,
        wmsQuantity: Int,
    ) {
        if (inventoryQuantity < 0 || wmsQuantity < 0 || inventoryQuantity == wmsQuantity) {
            throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
        }
        this.inventoryQuantity = inventoryQuantity
        this.wmsQuantity = wmsQuantity
        this.quantityChange = wmsQuantity - inventoryQuantity
    }

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
            return StockAdjustmentItem(null, null, lotId, qualityStatus, quantityChange, null, null)
        }

        /** 대사 보정안의 항목. 변동량은 WMS 수량 − inventory 수량이다 */
        fun reconciliation(
            lotId: Long,
            qualityStatus: QualityStatus,
            inventoryQuantity: Int,
            wmsQuantity: Int,
        ): StockAdjustmentItem {
            if (lotId < 1 || inventoryQuantity < 0 || wmsQuantity < 0 || inventoryQuantity == wmsQuantity) {
                throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
            }
            return StockAdjustmentItem(null, null, lotId, qualityStatus, wmsQuantity - inventoryQuantity, inventoryQuantity, wmsQuantity)
        }

        fun reconstitute(
            stockAdjustmentItemId: Long,
            inventoryId: Long?,
            lotId: Long,
            qualityStatus: QualityStatus,
            quantityChange: Int,
            inventoryQuantity: Int? = null,
            wmsQuantity: Int? = null,
        ): StockAdjustmentItem =
            StockAdjustmentItem(stockAdjustmentItemId, inventoryId, lotId, qualityStatus, quantityChange, inventoryQuantity, wmsQuantity)
    }
}
