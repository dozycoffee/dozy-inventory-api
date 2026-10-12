package com.dozycoffee.inventory.inventory.application.port.`in`.result

import com.dozycoffee.inventory.global.domain.QualityStatus

/** 폐기 반영 결과. [quantityChange]는 음수이고 [quantityAfter]는 반영 직후의 총 수량이며 재요청에도 처음 응답과 같은 값이다 */
data class DisposalResult(
    val inventoryId: Long,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    val quantityChange: Int,
    val quantityAfter: Int,
    val inventoryHistoryId: Long,
)
