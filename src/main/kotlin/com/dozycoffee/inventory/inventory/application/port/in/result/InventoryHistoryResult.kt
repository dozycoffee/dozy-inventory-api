package com.dozycoffee.inventory.inventory.application.port.`in`.result

import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import java.time.LocalDateTime

/** 재고 수량 변동 원장의 한 행. [quantityAfter]는 변경 후 총 수량이다 */
data class InventoryHistoryResult(
    val inventoryHistoryId: Long,
    val historyType: HistoryType,
    val quantityChange: Int,
    val quantityAfter: Int,
    val referenceType: ReferenceType,
    val referenceId: Long,
    val idempotencyKey: String,
    val requesterService: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(history: InventoryHistory): InventoryHistoryResult =
            InventoryHistoryResult(
                inventoryHistoryId = checkNotNull(history.inventoryHistoryId) { "저장된 이력은 식별자가 있어야 한다" },
                historyType = history.historyType,
                quantityChange = history.quantityChange,
                quantityAfter = history.quantityAfter,
                referenceType = history.referenceType,
                referenceId = history.referenceId,
                idempotencyKey = history.idempotencyKey.value,
                requesterService = history.requesterService.value,
                createdAt = checkNotNull(history.createdAt) { "저장된 이력은 생성 시각이 있어야 한다" },
            )
    }
}
