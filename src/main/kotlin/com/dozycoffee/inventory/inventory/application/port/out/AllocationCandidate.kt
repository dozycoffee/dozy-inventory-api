package com.dozycoffee.inventory.inventory.application.port.out

import java.time.LocalDate

/** 예약할 수 있는 재고 행. 정상 품질이고 할당 보류가 아니며 가용 수량이 있고 유통기한이 지나지 않은 Lot의 행이다 */
data class AllocationCandidate(
    val productId: Long,
    val inventoryId: Long,
    val lotId: Long,
    val expirationDate: LocalDate?,
    val availableQuantity: Int,
)
