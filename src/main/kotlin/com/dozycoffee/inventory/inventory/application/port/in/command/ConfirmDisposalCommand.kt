package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.QualityStatus

/**
 * 폐기 확정 한 품목. [referenceId]는 WMS의 폐기 상품 ID이고 [requesterService]는 요청 주체(system client의 principalId)다.
 * 폐기할 재고는 `상품 + Lot 번호`로 찾은 Lot의 [qualityStatus] 행이며 불량 또는 폐기 예정 품질 상태만 가능하다.
 */
data class ConfirmDisposalCommand(
    val warehouseId: Long,
    val productId: Long,
    val lotNumber: String,
    val qualityStatus: QualityStatus,
    val quantity: Int,
    val referenceId: Long,
    val idempotencyKey: String,
    val requesterService: String,
)
