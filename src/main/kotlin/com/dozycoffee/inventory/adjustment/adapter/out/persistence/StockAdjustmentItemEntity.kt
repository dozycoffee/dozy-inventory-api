package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.global.domain.QualityStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("stock_adjustment_item")
class StockAdjustmentItemEntity(
    @Id
    @Column("stock_adjustment_item_id")
    var stockAdjustmentItemId: Long? = null,
    var stockAdjustmentId: Long,
    var inventoryId: Long?,
    var lotId: Long,
    var qualityStatus: QualityStatus,
    var quantityChange: Int,
) : BaseEntity() {
    fun toDomain(): StockAdjustmentItem =
        StockAdjustmentItem.reconstitute(
            stockAdjustmentItemId = checkNotNull(stockAdjustmentItemId) { "저장된 조정 항목은 식별자가 있어야 한다" },
            inventoryId = inventoryId,
            lotId = lotId,
            qualityStatus = qualityStatus,
            quantityChange = quantityChange,
        )

    companion object {
        fun from(
            stockAdjustmentId: Long,
            item: StockAdjustmentItem,
        ): StockAdjustmentItemEntity =
            StockAdjustmentItemEntity(
                stockAdjustmentItemId = item.stockAdjustmentItemId,
                stockAdjustmentId = stockAdjustmentId,
                inventoryId = item.inventoryId,
                lotId = item.lotId,
                qualityStatus = item.qualityStatus,
                quantityChange = item.quantityChange,
            )
    }
}
