package com.dozycoffee.inventory.inventory.application.port.`in`.result

/** 재고 행별 출고 반영 결과. [Item.quantityAfter]는 출고한 행의 처리 후 총 수량이고 출고하지 않은 행은 총 수량이 바뀌지 않아 null이다 */
data class ShipResult(
    val items: List<Item>,
) {
    data class Item(
        val inventoryId: Long,
        val quantityAfter: Int?,
    )
}
