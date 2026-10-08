package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType

/** 재고 변동 이벤트. 구독자(OMS, 가맹점)는 [quantityAfter]로 가용 재고 캐시를 갱신한다 */
data class InventoryEvent(
    val eventType: InventoryEventType,
    val warehouseId: Long,
    val productId: Long,
    val lotId: Long,
    val qualityStatus: QualityStatus,
    val quantityChange: Int,
    val quantityAfter: Int,
    val referenceType: ReferenceType,
    val referenceId: Long,
    val idempotencyKey: String,
)

enum class InventoryEventType {
    INCREASED,
    DECREASED,
}
