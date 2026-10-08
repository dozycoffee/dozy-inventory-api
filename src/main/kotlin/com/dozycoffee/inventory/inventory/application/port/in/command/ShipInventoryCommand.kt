package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/**
 * 재고 행별 출고 반영. [Item.shippedQuantity]는 실제 출고한 수량이고 [Item.releasedQuantity]는 출고하지 못해 가용으로 되돌릴
 * 예약 수량(결품)이다. 행마다 둘의 합은 1 이상이다. [referenceId]는 원인 문서(예약) ID이고 [requesterService]는 요청 주체다.
 */
data class ShipInventoryCommand(
    val items: List<Item>,
    val idempotencyKey: String,
    val referenceId: Long,
    val requesterService: String,
) {
    data class Item(
        val inventoryId: Long,
        val shippedQuantity: Int,
        val releasedQuantity: Int,
    )

    init {
        if (items.isEmpty() ||
            items.any {
                it.inventoryId < 1 || it.shippedQuantity < 0 || it.releasedQuantity < 0 ||
                    it.shippedQuantity + it.releasedQuantity < 1
            } ||
            items.map(Item::inventoryId).toSet().size != items.size ||
            referenceId < 1
        ) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_SHIP_REQUEST)
        }
    }
}
