package com.dozycoffee.inventory.inventory.application.port.`in`.result

import java.time.LocalDate

/** 재고 행의 Lot 정보. [expirationDate]는 유통기한이 없는 Lot이면 null이다 */
data class InventoryLotResult(
    val inventoryId: Long,
    val lotId: Long,
    val lotNumber: String,
    val expirationDate: LocalDate?,
)
