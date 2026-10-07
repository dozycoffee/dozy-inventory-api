package com.dozycoffee.ims.inventory.adapter.out.persistence

import com.dozycoffee.ims.global.common.CreatedAuditEntity
import com.dozycoffee.ims.inventory.domain.enumeration.HistoryType
import com.dozycoffee.ims.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.ims.inventory.domain.model.InventoryHistory
import com.dozycoffee.ims.inventory.domain.valueobject.IdempotencyKey
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/** 변경하지 않는 원장이라 생성 정보만 가진다(`inventory_history`에는 수정 컬럼이 없다) */
@Table("inventory_history")
class InventoryHistoryEntity(
    @Id
    @Column("inventory_history_id")
    var inventoryHistoryId: Long? = null,
    var inventoryId: Long,
    var historyType: HistoryType,
    var quantityChange: Int,
    var quantityAfter: Int,
    var referenceType: ReferenceType,
    var referenceId: Long,
    var idempotencyKey: String,
    var requesterService: String,
) : CreatedAuditEntity() {
    fun toDomain(): InventoryHistory =
        InventoryHistory.reconstitute(
            inventoryHistoryId = checkNotNull(inventoryHistoryId) { "저장된 이력은 식별자가 있어야 한다" },
            inventoryId = inventoryId,
            historyType = historyType,
            quantityChange = quantityChange,
            quantityAfter = quantityAfter,
            referenceType = referenceType,
            referenceId = referenceId,
            idempotencyKey = IdempotencyKey.of(idempotencyKey),
            requesterService = requesterService,
            createdAt = checkNotNull(createdAt) { "저장된 이력은 생성 시각이 있어야 한다" },
        )

    companion object {
        fun from(history: InventoryHistory): InventoryHistoryEntity =
            InventoryHistoryEntity(
                inventoryHistoryId = history.inventoryHistoryId,
                inventoryId = history.inventoryId,
                historyType = history.historyType,
                quantityChange = history.quantityChange,
                quantityAfter = history.quantityAfter,
                referenceType = history.referenceType,
                referenceId = history.referenceId,
                idempotencyKey = history.idempotencyKey.value,
                requesterService = history.requesterService,
            )
    }
}
