package com.dozycoffee.inventory.access.adapter.`in`.event

import com.dozycoffee.inventory.access.application.port.`in`.command.ReplaceWarehouseAccessCommand
import java.time.OffsetDateTime

/**
 * WMS가 보내는 직원 창고 소속 변경 이벤트(`docs/events.md`의 수신 이벤트 계약). 공통 형식(envelope, ADR-0038)만 받는다.
 * 평면 형식(최상위 `principalId`)은 `data`가 없어 거부한다. 필드 이름은 계약이다. [occurredAt]은 추적용이라 처리에는 쓰지 않고
 * 알 수 없는 필드는 무시한다.
 */
data class WarehouseAccessChangedMessage(
    val eventId: String?,
    val eventType: String?,
    val eventVersion: Int?,
    val occurredAt: OffsetDateTime?,
    val source: String?,
    val data: Data?,
) {
    /** envelope의 `data`. 필드 규칙(UUID, 중복 없는 양수 등)은 처리할 때 검증한다 */
    data class Data(
        val principalId: String?,
        val warehouseIds: List<Long>?,
    )

    /** 유형·버전·발행처가 맞지 않거나 `data`·`warehouseIds`가 없으면 [IllegalArgumentException]이다 */
    fun toCommand(): ReplaceWarehouseAccessCommand {
        require(eventType == EVENT_TYPE) { "알 수 없는 이벤트 유형이다: $eventType" }
        require(eventVersion == SUPPORTED_VERSION) { "지원하지 않는 이벤트 버전이다: $eventVersion" }
        require(source == SOURCE) { "알 수 없는 발행처다: $source" }
        requireNotNull(data) { "data가 없다" }
        requireNotNull(data.warehouseIds) { "warehouseIds가 없다" }
        return ReplaceWarehouseAccessCommand(data.principalId, data.warehouseIds)
    }

    companion object {
        const val EVENT_TYPE: String = "WAREHOUSE_ACCESS_CHANGED"
        const val SUPPORTED_VERSION: Int = 1
        const val SOURCE: String = "wms"
    }
}
