package com.dozycoffee.inventory.inventory.application.port.`in`.result

import com.dozycoffee.inventory.global.domain.QualityStatus

/** 창고의 재고 행 하나의 총 수량(예약 수량 제외). 대사가 WMS 수량과 비교한다. [productId], [lotNumber], [qualityStatus]가 WMS와 맞추는 키이다 */
data class WarehouseQuantityResult(
    val inventoryId: Long,
    val productId: Long,
    val lotId: Long,
    val lotNumber: String,
    val qualityStatus: QualityStatus,
    val quantity: Int,
)
