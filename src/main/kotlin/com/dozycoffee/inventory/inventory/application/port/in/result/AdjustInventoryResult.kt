package com.dozycoffee.inventory.inventory.application.port.`in`.result

/** 항목별 반영 결과. 요청의 항목 순서와 같다. [Item.quantityAfter]는 반영 후 총 수량이다 */
data class AdjustInventoryResult(
    val items: List<Item>,
) {
    data class Item(
        val referenceId: Long,
        val inventoryId: Long,
        val quantityAfter: Int,
    )
}
