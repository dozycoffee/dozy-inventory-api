package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/** 재고 행별로 되돌릴 예약 수량. 같은 재고 행을 두 번 담을 수 없다 */
data class ReleaseInventoryCommand(
    val items: List<Item>,
) {
    data class Item(
        val inventoryId: Long,
        val quantity: Int,
    )

    init {
        if (items.isEmpty() || items.any { it.inventoryId < 1 || it.quantity < 1 } ||
            items.map(Item::inventoryId).toSet().size != items.size
        ) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_RELEASE_REQUEST)
        }
    }
}
