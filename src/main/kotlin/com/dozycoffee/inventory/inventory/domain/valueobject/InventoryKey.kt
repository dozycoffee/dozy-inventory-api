package com.dozycoffee.inventory.inventory.domain.valueobject

import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus

/** 재고 행을 식별하는 키. 같은 창고, Lot, 품질 상태의 재고는 한 행이다 */
data class InventoryKey(
    val warehouseId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
)
