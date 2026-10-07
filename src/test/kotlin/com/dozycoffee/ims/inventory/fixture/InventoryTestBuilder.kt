package com.dozycoffee.ims.inventory.fixture

import com.dozycoffee.ims.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.ims.inventory.domain.model.Inventory
import java.time.LocalDateTime

class InventoryTestBuilder {
    private var inventoryId: Long = 1L
    private var warehouseId: Long = 10L
    private var productId: Long = 100L
    private var lotId: Long = 1000L
    private var qualityStatus: QualityStatus = QualityStatus.NORMAL
    private var quantity: Int = 10
    private var reservedQuantity: Int = 0
    private var allocationHold: Boolean = false
    private var holdReason: String? = null
    private var heldAt: LocalDateTime? = null

    fun qualityStatus(qualityStatus: QualityStatus): InventoryTestBuilder = apply { this.qualityStatus = qualityStatus }

    fun quantity(quantity: Int): InventoryTestBuilder = apply { this.quantity = quantity }

    fun reservedQuantity(reservedQuantity: Int): InventoryTestBuilder = apply { this.reservedQuantity = reservedQuantity }

    fun held(
        reason: String,
        at: LocalDateTime,
    ): InventoryTestBuilder =
        apply {
            allocationHold = true
            holdReason = reason
            heldAt = at
        }

    fun build(): Inventory =
        Inventory.reconstitute(
            inventoryId,
            warehouseId,
            productId,
            lotId,
            qualityStatus,
            quantity,
            reservedQuantity,
            allocationHold,
            holdReason,
            heldAt,
        )
}
