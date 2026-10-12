package com.dozycoffee.inventory.adjustment.adapter.out.wms

import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.global.domain.QualityStatus

/**
 * WMS 창고 수량 조회 API(`GET /api/v1/stock-quantities`)의 응답 본문(ADR-0033). 필드 이름은 WMS와 맞춘 계약이다.
 * 알 수 없는 필드는 무시하고, 필수 필드가 없거나 값이 올바르지 않으면 [toQuantities]가 [IllegalArgumentException]을 던진다.
 */
data class WmsStockPayload(
    val items: List<Item>?,
) {
    data class Item(
        val productId: Long?,
        val lotNumber: String?,
        val qualityStatus: String?,
        val quantity: Int?,
    )

    fun toQuantities(): List<WmsStockQuantity> =
        requireNotNull(items) { "items가 없다" }.map { item: Item ->
            val productId: Long = requireNotNull(item.productId) { "productId가 없다" }
            val lotNumber: String = requireNotNull(item.lotNumber?.takeIf { it.isNotBlank() }) { "lotNumber가 없다" }
            val quantity: Int = requireNotNull(item.quantity) { "quantity가 없다" }
            require(productId >= 1 && quantity >= 0) { "productId는 양수, quantity는 0 이상이어야 한다" }
            WmsStockQuantity(
                productId,
                lotNumber,
                QualityStatus.valueOf(requireNotNull(item.qualityStatus) { "qualityStatus가 없다" }),
                quantity,
            )
        }
}
