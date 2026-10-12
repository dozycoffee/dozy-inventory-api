package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import java.time.LocalDateTime

data class InventoryHistoryResponse(
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
        fun from(result: InventoryHistoryResult): InventoryHistoryResponse =
            InventoryHistoryResponse(
                inventoryHistoryId = result.inventoryHistoryId,
                historyType = result.historyType,
                quantityChange = result.quantityChange,
                quantityAfter = result.quantityAfter,
                referenceType = result.referenceType,
                referenceId = result.referenceId,
                idempotencyKey = result.idempotencyKey,
                requesterService = result.requesterService,
                createdAt = result.createdAt,
            )
    }
}
