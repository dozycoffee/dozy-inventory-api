package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** 재고 행과 그 Lot을 합친 조회용 행. 관리자 현황과 상세에 쓴다 */
data class InventoryRow(
    val inventoryId: Long,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val lotNumber: String,
    val manufactureDate: LocalDate?,
    val expirationDate: LocalDate?,
    val lotStatus: LotStatus,
    val qualityStatus: QualityStatus,
    val quantity: Int,
    val reservedQuantity: Int,
    val allocationHold: Boolean,
    val holdReason: String?,
    val heldAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    val availableQuantity: Int
        get() = quantity - reservedQuantity
}

/** 재고 현황의 필터. 값이 null인 조건은 적용하지 않는다 */
data class InventorySearchCondition(
    val warehouseId: Long?,
    val productId: Long?,
    val qualityStatus: QualityStatus?,
    val lotStatus: LotStatus?,
    val allocationHold: Boolean?,
)
