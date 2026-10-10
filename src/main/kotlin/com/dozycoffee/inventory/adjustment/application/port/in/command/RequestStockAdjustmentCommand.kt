package com.dozycoffee.inventory.adjustment.application.port.`in`.command

import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/**
 * WMS의 실사 조정 요청. [externalReferenceId]는 WMS 실사 건 ID이고 항목은 `상품 × Lot × 품질 상태`별 변동량(+/−)이다.
 * [approvedBy]는 승인이 필요한 변동량이 있을 때 WMS가 보내는 승인자이며 없을 수 있다. [requesterService]는 요청 주체다.
 */
data class RequestStockAdjustmentCommand(
    val warehouseId: Long,
    val externalReferenceId: Long,
    val items: List<Item>,
    val approvedBy: String?,
    val idempotencyKey: String,
    val requesterService: String,
) {
    data class Item(
        val productId: Long,
        val lotNumber: String,
        val qualityStatus: QualityStatus,
        val quantityChange: Int,
    )

    init {
        if (warehouseId < 1 ||
            externalReferenceId < 1 ||
            items.isEmpty() ||
            items.size > MAX_ITEMS ||
            items.any {
                it.productId < 1 || it.lotNumber.isBlank() || it.lotNumber.length > MAX_LOT_NUMBER_LENGTH || it.quantityChange == 0
            } ||
            items.map { Triple(it.productId, it.lotNumber, it.qualityStatus) }.toSet().size != items.size
        ) {
            throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT)
        }
    }

    companion object {
        const val MAX_ITEMS: Int = 500
        const val MAX_LOT_NUMBER_LENGTH: Int = 50
    }
}
