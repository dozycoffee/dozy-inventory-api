package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationRevision
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import java.time.LocalDateTime

/**
 * 조정 요청 한 건과 그 항목들(ADR-0026, ADR-0033). 대사 보정(`RECONCILIATION`)은 불일치한 행 하나가 보정안 한 건이고 `PENDING`으로 시작해 승인되면
 * `APPLIED`, 거부되면 `REJECTED`가 된다.
 *
 * 실사 조정(`AUDIT`)은 요청을 받은 트랜잭션에서 바로 반영하므로 `APPLIED`로 만든다.
 * [externalReferenceId]는 WMS 실사 건 ID이고 [approvedBy]는 승인이 필요한 변동량이 있을 때 WMS가 보낸 승인자다.
 * 승인자가 있으면 [approvedAt]도 있다. 항목은 같은 Lot과 품질 상태를 두 번 담지 않는다.
 */
class StockAdjustment private constructor(
    val stockAdjustmentId: Long?,
    val warehouseId: Long,
    val adjustmentType: AdjustmentType,
    status: AdjustmentStatus,
    val externalReferenceId: Long?,
    val reconciliationRunId: Long?,
    val requestedBy: RequesterService,
    approvedBy: String?,
    approvedAt: LocalDateTime?,
    val idempotencyKey: IdempotencyKey,
    val items: List<StockAdjustmentItem>,
) {
    var status: AdjustmentStatus = status
        private set

    var approvedBy: String? = approvedBy
        private set

    var approvedAt: LocalDateTime? = approvedAt
        private set

    /**
     * 처리 대기 중인 대사 보정안을 승인해 반영 상태로 만든다(ADR-0033). 승인과 반영이 한 단계라 `APPLIED`가 된다.
     * 이미 반영되었으면 바꾸지 않고 false를 반환한다. 대사 보정안이 아니거나 거부된 보정안은 [InvalidAdjustmentStateException]이다.
     * 재고에 반영하는 일은 호출한 서비스가 한다.
     */
    fun approve(
        approver: String,
        now: LocalDateTime,
    ): Boolean {
        requireReconciliation()
        return when (status) {
            AdjustmentStatus.APPLIED -> {
                false
            }

            AdjustmentStatus.PENDING -> {
                approvedBy = requireValidApprover(approver)
                approvedAt = now
                status = AdjustmentStatus.APPLIED
                true
            }

            else -> {
                throw InvalidAdjustmentStateException()
            }
        }
    }

    /** 처리 대기 중인 대사 보정안을 거부한다. 이미 거부되었으면 바꾸지 않고 false를 반환하고, 반영된 보정안은 [InvalidAdjustmentStateException]이다 */
    fun reject(): Boolean {
        requireReconciliation()
        return when (status) {
            AdjustmentStatus.REJECTED -> {
                false
            }

            AdjustmentStatus.PENDING -> {
                status = AdjustmentStatus.REJECTED
                true
            }

            else -> {
                throw InvalidAdjustmentStateException()
            }
        }
    }

    /**
     * 승인 시점에 다시 확인한 두 수량으로 보정안을 고친다(ADR-0033). 보정안의 값과 같으면 [ReconciliationRevision.UNCHANGED],
     * 다르면 수량과 변동량을 새 값으로 바꾸고 [ReconciliationRevision.UPDATED], 두 수량이 같아져 불일치가 사라졌으면 보정안을 거부 상태로 닫고
     * [ReconciliationRevision.RESOLVED]이다. 처리 대기 중인 대사 보정안만 고칠 수 있다.
     */
    fun revise(
        inventoryQuantity: Int,
        wmsQuantity: Int,
    ): ReconciliationRevision {
        requireReconciliation()
        if (status != AdjustmentStatus.PENDING) throw InvalidAdjustmentStateException()
        val item: StockAdjustmentItem = items.single()
        if (item.inventoryQuantity == inventoryQuantity && item.wmsQuantity == wmsQuantity) return ReconciliationRevision.UNCHANGED
        if (inventoryQuantity == wmsQuantity) {
            status = AdjustmentStatus.REJECTED
            return ReconciliationRevision.RESOLVED
        }
        item.revise(inventoryQuantity, wmsQuantity)
        return ReconciliationRevision.UPDATED
    }

    private fun requireReconciliation() {
        if (adjustmentType != AdjustmentType.RECONCILIATION) throw InvalidAdjustmentStateException()
    }

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
                null,
                requestedBy,
                approver,
                approver?.let { now },
                idempotencyKey,
                items.toList(),
            )
        }

        /** 대사가 불일치한 행 하나로 만든 처리 대기 보정안. 요청자는 시스템이고 승인은 사람이 한다 */
        fun reconciliation(
            warehouseId: Long,
            reconciliationRunId: Long,
            item: StockAdjustmentItem,
            requestedBy: RequesterService,
            idempotencyKey: IdempotencyKey,
        ): StockAdjustment {
            if (warehouseId < 1 || reconciliationRunId < 1) throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
            return StockAdjustment(
                null,
                warehouseId,
                AdjustmentType.RECONCILIATION,
                AdjustmentStatus.PENDING,
                null,
                reconciliationRunId,
                requestedBy,
                null,
                null,
                idempotencyKey,
                listOf(item),
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
            reconciliationRunId: Long? = null,
        ): StockAdjustment =
            StockAdjustment(
                stockAdjustmentId,
                warehouseId,
                adjustmentType,
                status,
                externalReferenceId,
                reconciliationRunId,
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
