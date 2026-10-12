package com.dozycoffee.inventory.access.adapter.`in`.event

import com.dozycoffee.inventory.access.application.port.`in`.command.ReplaceWarehouseAccessCommand
import java.time.OffsetDateTime

/**
 * WMS가 보내는 직원 창고 소속 변경 이벤트(`docs/events.md`의 수신 이벤트 계약). 필드 이름은 계약이다.
 * [eventId]와 [occurredAt]은 추적용이라 처리에는 쓰지 않는다. 알 수 없는 필드는 무시한다.
 */
data class WarehouseAccessChangedMessage(
    val eventType: String?,
    val eventId: String?,
    val occurredAt: OffsetDateTime?,
    val principalId: String?,
    val warehouseIds: List<Long>?,
) {
    /** 유형이 `WAREHOUSE_ACCESS_CHANGED`가 아니거나 `warehouseIds`가 없으면 [IllegalArgumentException]이다 */
    fun toCommand(): ReplaceWarehouseAccessCommand {
        require(eventType == EVENT_TYPE) { "알 수 없는 이벤트 유형이다: $eventType" }
        requireNotNull(warehouseIds) { "warehouseIds가 없다" }
        return ReplaceWarehouseAccessCommand(principalId, warehouseIds)
    }

    companion object {
        const val EVENT_TYPE: String = "WAREHOUSE_ACCESS_CHANGED"
    }
}
