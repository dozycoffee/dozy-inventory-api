package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/**
 * 실사 조정의 재고 반영 요청. 항목은 `Lot × 품질 상태`별 변동량(+/−)이며 [Item.referenceId]는 이력의 원인 문서(조정 항목) ID다.
 * 항목은 1개 이상 [MAX_ITEMS]개 이하이고 같은 Lot과 품질 상태, 같은 원인 문서가 두 번 나오면 안 된다.
 * [requesterService]는 요청 주체다.
 */
data class AdjustInventoryCommand(
    val warehouseId: Long,
    val items: List<Item>,
    val idempotencyKey: String,
    val requesterService: String,
) {
    data class Item(
        val productId: Long,
        val lotId: Long,
        val qualityStatus: QualityStatus,
        val quantityChange: Int,
        val referenceId: Long,
    )

    init {
        if (warehouseId < 1 ||
            items.isEmpty() ||
            items.size > MAX_ITEMS ||
            items.any { it.productId < 1 || it.lotId < 1 || it.referenceId < 1 || it.quantityChange == 0 } ||
            items.map { it.lotId to it.qualityStatus }.toSet().size != items.size ||
            items.map(Item::referenceId).toSet().size != items.size
        ) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_ADJUST_REQUEST)
        }
    }

    companion object {
        const val MAX_ITEMS: Int = 500
    }
}
