package com.dozycoffee.ims.inventory.application.port.`in`.command

import com.dozycoffee.ims.inventory.domain.enumeration.QualityStatus
import java.time.LocalDate

/**
 * 입고 확정 한 품목. [referenceId]는 WMS의 입고 상품 ID이고 [requesterService]는 요청 주체(system client의 principalId)다.
 * Lot 번호, 제조일자, 유통기한은 공급사가 부여한 값 그대로 쓴다.
 */
data class ConfirmInboundCommand(
    val warehouseId: Long,
    val productId: Long,
    val quantity: Int,
    val qualityStatus: QualityStatus,
    val lotNumber: String,
    val manufactureDate: LocalDate?,
    val expirationDate: LocalDate?,
    val referenceId: Long,
    val idempotencyKey: String,
    val requesterService: String,
)
