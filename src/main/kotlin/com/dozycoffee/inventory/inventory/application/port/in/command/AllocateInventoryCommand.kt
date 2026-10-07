package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/** 한 창고에서 상품별로 잡을 수량. 호출 서비스가 확정한 창고 하나만 받는다. 상품은 중복될 수 없다 */
data class AllocateInventoryCommand(
    val warehouseId: Long,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val quantity: Int,
    )

    init {
        if (warehouseId < 1 ||
            items.isEmpty() ||
            items.size > MAX_ITEMS ||
            items.any { it.productId < 1 || it.quantity < 1 } ||
            items.map(Item::productId).toSet().size != items.size
        ) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_ALLOCATION_REQUEST)
        }
    }

    companion object {
        const val MAX_ITEMS: Int = 100
    }
}
