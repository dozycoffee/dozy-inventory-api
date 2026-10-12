package com.dozycoffee.inventory.inventory.application.port.`in`.result

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** 관리자가 보는 재고 행. [availableQuantity]는 총 수량에서 예약 수량을 뺀 값이며 품질 상태와 보류는 반영하지 않은 행 자체의 값이다 */
data class InventoryResult(
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
    val availableQuantity: Int,
    val allocationHold: Boolean,
    val holdReason: String?,
    val heldAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
