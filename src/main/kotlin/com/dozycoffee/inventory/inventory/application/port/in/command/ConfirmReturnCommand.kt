package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus

/**
 * 반품 복귀 한 품목. [referenceId]는 WMS의 반품 상품 ID이고 [requesterService]는 요청 주체(system client의 principalId)다.
 * 복귀할 재고는 `상품 + Lot 번호`로 찾은 등록된 Lot의 [qualityStatus] 행이며 품질 상태는 WMS 검수 판정(정상 또는 불량)이다.
 */
data class ConfirmReturnCommand(
    val warehouseId: Long,
    val productId: Long,
    val lotNumber: String,
    val qualityStatus: QualityStatus,
    val quantity: Int,
    val referenceId: Long,
    val idempotencyKey: String,
    val requesterService: String,
)
