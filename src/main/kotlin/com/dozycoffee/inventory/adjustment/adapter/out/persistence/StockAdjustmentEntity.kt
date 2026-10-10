package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("stock_adjustment")
class StockAdjustmentEntity(
    @Id
    @Column("stock_adjustment_id")
    var stockAdjustmentId: Long? = null,
    var warehouseId: Long,
    var adjustmentType: AdjustmentType,
    var status: AdjustmentStatus,
    var externalReferenceId: Long?,
    var requestedBy: String,
    var approvedBy: String?,
    var approvedAt: LocalDateTime?,
    var idempotencyKey: String,
) : BaseEntity() {
    fun toDomain(items: List<StockAdjustmentItem>): StockAdjustment =
        StockAdjustment.reconstitute(
            stockAdjustmentId = checkNotNull(stockAdjustmentId) { "저장된 조정은 식별자가 있어야 한다" },
            warehouseId = warehouseId,
            adjustmentType = adjustmentType,
            status = status,
            externalReferenceId = externalReferenceId,
            requestedBy = RequesterService.of(requestedBy),
            approvedBy = approvedBy,
            approvedAt = approvedAt,
            idempotencyKey = IdempotencyKey.of(idempotencyKey),
            items = items,
        )

    companion object {
        fun from(adjustment: StockAdjustment): StockAdjustmentEntity =
            StockAdjustmentEntity(
                stockAdjustmentId = adjustment.stockAdjustmentId,
                warehouseId = adjustment.warehouseId,
                adjustmentType = adjustment.adjustmentType,
                status = adjustment.status,
                externalReferenceId = adjustment.externalReferenceId,
                requestedBy = adjustment.requestedBy.value,
                approvedBy = adjustment.approvedBy,
                approvedAt = adjustment.approvedAt,
                idempotencyKey = adjustment.idempotencyKey.value,
            )
    }
}
