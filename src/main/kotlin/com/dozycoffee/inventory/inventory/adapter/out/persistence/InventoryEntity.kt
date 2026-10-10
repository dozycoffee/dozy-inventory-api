package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

/** 재고 행의 조회용 매핑. 수량 변경은 원자적 SQL로 하므로 엔티티를 저장하지 않는다 */
@Table("inventory")
class InventoryEntity(
    @Id
    @Column("inventory_id")
    var inventoryId: Long? = null,
    var warehouseId: Long,
    var productId: Long,
    var lotId: Long,
    var qualityStatus: QualityStatus,
    var quantity: Int,
    var reservedQuantity: Int,
    var allocationHold: Boolean,
    var holdReason: String?,
    var heldAt: LocalDateTime?,
) : BaseEntity() {
    fun toDomain(): Inventory =
        Inventory.reconstitute(
            inventoryId = checkNotNull(inventoryId) { "저장된 재고는 식별자가 있어야 한다" },
            warehouseId = warehouseId,
            productId = productId,
            lotId = lotId,
            qualityStatus = qualityStatus,
            quantity = quantity,
            reservedQuantity = reservedQuantity,
            allocationHold = allocationHold,
            holdReason = holdReason,
            heldAt = heldAt,
        )
}
