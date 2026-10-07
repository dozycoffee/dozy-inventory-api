package com.dozycoffee.inventory.inventory.application.port.`in`.result

import java.time.LocalDate

/** 요청한 상품 순서대로 상품별 할당 결과. 한 상품이 여러 Lot에 걸칠 수 있고 유통기한이 이른 Lot부터 나온다 */
data class AllocationResult(
    val items: List<ItemAllocation>,
) {
    data class ItemAllocation(
        val productId: Long,
        val lots: List<LotAllocation>,
    )

    data class LotAllocation(
        val inventoryId: Long,
        val lotId: Long,
        val expirationDate: LocalDate?,
        val quantity: Int,
    )
}
