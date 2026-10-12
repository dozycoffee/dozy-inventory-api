package com.dozycoffee.inventory.inventory.adapter.`in`.web.response

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import java.time.LocalDate
import java.time.LocalDateTime

data class InventoryResponse(
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
) {
    companion object {
        fun from(result: InventoryResult): InventoryResponse =
            InventoryResponse(
                inventoryId = result.inventoryId,
                warehouseId = result.warehouseId,
                productId = result.productId,
                lotId = result.lotId,
                lotNumber = result.lotNumber,
                manufactureDate = result.manufactureDate,
                expirationDate = result.expirationDate,
                lotStatus = result.lotStatus,
                qualityStatus = result.qualityStatus,
                quantity = result.quantity,
                reservedQuantity = result.reservedQuantity,
                availableQuantity = result.availableQuantity,
                allocationHold = result.allocationHold,
                holdReason = result.holdReason,
                heldAt = result.heldAt,
                createdAt = result.createdAt,
                updatedAt = result.updatedAt,
            )
    }
}
