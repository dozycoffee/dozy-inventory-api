package com.dozycoffee.inventory.inventory.domain.model

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import java.time.LocalDateTime

/**
 * 재고 수량 변동 원장의 한 행. 만든 뒤에는 바뀌지 않는다.
 * [quantityAfter]는 변경 후 총 수량이다(예약 수량 제외, ERD-02). [createdAt]은 저장소에서 복원한 이력에만 있다.
 */
class InventoryHistory private constructor(
    val inventoryHistoryId: Long?,
    val inventoryId: Long,
    val historyType: HistoryType,
    val quantityChange: Int,
    val quantityAfter: Int,
    val referenceType: ReferenceType,
    val referenceId: Long,
    val idempotencyKey: IdempotencyKey,
    val requesterService: RequesterService,
    val createdAt: LocalDateTime?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InventoryHistory) return false
        return inventoryHistoryId != null && inventoryHistoryId == other.inventoryHistoryId
    }

    override fun hashCode(): Int = inventoryHistoryId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            inventoryId: Long,
            historyType: HistoryType,
            quantityChange: Int,
            quantityAfter: Int,
            referenceType: ReferenceType,
            referenceId: Long,
            idempotencyKey: IdempotencyKey,
            requesterService: RequesterService,
        ): InventoryHistory {
            if (!isValidChange(historyType, quantityChange)) throw InvalidDomainValueException(InventoryErrorCode.INVALID_HISTORY_CHANGE)
            if (quantityAfter < 0) throw InvalidDomainValueException(InventoryErrorCode.INVALID_HISTORY_AFTER)
            return InventoryHistory(
                null,
                inventoryId,
                historyType,
                quantityChange,
                quantityAfter,
                referenceType,
                referenceId,
                idempotencyKey,
                requesterService,
                null,
            )
        }

        fun reconstitute(
            inventoryHistoryId: Long,
            inventoryId: Long,
            historyType: HistoryType,
            quantityChange: Int,
            quantityAfter: Int,
            referenceType: ReferenceType,
            referenceId: Long,
            idempotencyKey: IdempotencyKey,
            requesterService: RequesterService,
            createdAt: LocalDateTime,
        ): InventoryHistory =
            InventoryHistory(
                inventoryHistoryId,
                inventoryId,
                historyType,
                quantityChange,
                quantityAfter,
                referenceType,
                referenceId,
                idempotencyKey,
                requesterService,
                createdAt,
            )

        /** 변동량은 0일 수 없고, 입고·반품은 증가, 출고·폐기는 감소여야 한다. 조정·대사·품질 전환은 양쪽 모두 가능하다 */
        private fun isValidChange(
            historyType: HistoryType,
            quantityChange: Int,
        ): Boolean =
            when (historyType) {
                HistoryType.INBOUND, HistoryType.RETURN -> quantityChange > 0
                HistoryType.OUTBOUND, HistoryType.DISPOSAL -> quantityChange < 0
                HistoryType.ADJUSTMENT, HistoryType.RECONCILIATION, HistoryType.QUALITY_TRANSFER -> quantityChange != 0
            }
    }
}
