package com.dozycoffee.inventory.inventory.application.port.out

import java.time.LocalDate

/** 재고 행이 속한 Lot의 정보. 이벤트처럼 재고 행 ID만 아는 쪽이 Lot을 알아볼 수 있게 한다 */
data class InventoryLotInfo(
    val inventoryId: Long,
    val lotId: Long,
    val lotNumber: String,
    val expirationDate: LocalDate?,
)
