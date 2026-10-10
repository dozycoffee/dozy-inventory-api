package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import java.time.LocalDateTime

/**
 * 조정 요청 한 건과 그 항목들(ADR-0026). 실사 조정(`AUDIT`)은 요청을 받은 트랜잭션에서 바로 반영하므로 `APPLIED`로 만든다.
 * [externalReferenceId]는 WMS 실사 건 ID이고 [approvedBy]는 승인이 필요한 변동량이 있을 때 WMS가 보낸 승인자다.
 * 승인자가 있으면 [approvedAt]도 있다. 항목은 같은 Lot과 품질 상태를 두 번 담지 않는다.
 */
class StockAdjustment private constructor(
    val stockAdjustmentId: Long?,
    val warehouseId: Long,
    val adjustmentType: AdjustmentType,
    val status: AdjustmentStatus,
    val externalReferenceId: Long?,
    val requestedBy: RequesterService,
    val approvedBy: String?,
    val approvedAt: LocalDateTime?,
    val idempotencyKey: IdempotencyKey,
    val items: List<StockAdjustmentItem>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StockAdjustment) return false
        return stockAdjustmentId != null && stockAdjustmentId == other.stockAdjustmentId
    }

    override fun hashCode(): Int = stockAdjustmentId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        const val MAX_ITEMS: Int = 500
        const val MAX_APPROVER_LENGTH: Int = 100

        /** 승인 시각은 [now]이다. 승인자가 없으면 승인 정보를 남기지 않는다 */
        fun audit(
            warehouseId: Long,
            externalReferenceId: Long,
            items: List<StockAdjustmentItem>,
            requestedBy: RequesterService,
            approvedBy: String?,
            idempotencyKey: IdempotencyKey,
            now: LocalDateTime,
        ): StockAdjustment {
            if (warehouseId < 1 ||
                externalReferenceId < 1 ||
                items.isEmpty() ||
                items.size > MAX_ITEMS ||
                items.map { it.lotId to it.qualityStatus }.toSet().size != items.size
            ) {
                throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT)
            }
            val approver: String? = approvedBy?.let { requireValidApprover(it) }
            return StockAdjustment(
                null,
                warehouseId,
                AdjustmentType.AUDIT,
                AdjustmentStatus.APPLIED,
                externalReferenceId,
                requestedBy,
                approver,
                approver?.let { now },
                idempotencyKey,
                items.toList(),
            )
        }

        fun reconstitute(
            stockAdjustmentId: Long,
            warehouseId: Long,
            adjustmentType: AdjustmentType,
            status: AdjustmentStatus,
            externalReferenceId: Long?,
            requestedBy: RequesterService,
            approvedBy: String?,
            approvedAt: LocalDateTime?,
            idempotencyKey: IdempotencyKey,
            items: List<StockAdjustmentItem>,
        ): StockAdjustment =
            StockAdjustment(
                stockAdjustmentId,
                warehouseId,
                adjustmentType,
                status,
                externalReferenceId,
                requestedBy,
                approvedBy,
                approvedAt,
                idempotencyKey,
                items.toList(),
            )

        private fun requireValidApprover(value: String): String {
            val valid: String = DomainValidator.requireNotBlank(value, AdjustmentErrorCode.INVALID_APPROVER)
            if (valid.length > MAX_APPROVER_LENGTH) throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_APPROVER)
            return valid
        }
    }
}
