package com.dozycoffee.inventory.inventory.application.port.`in`.result

import com.dozycoffee.inventory.global.domain.QualityStatus

/** 입고 반영 결과. [quantityAfter]는 반영 직후의 총 수량이며 재요청에도 처음 응답과 같은 값이다(WMS가 자기 수량과 비교한다) */
data class InboundResult(
    val inventoryId: Long,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    val quantityChange: Int,
    val quantityAfter: Int,
    val inventoryHistoryId: Long,
)
