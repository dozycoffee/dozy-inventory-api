package com.dozycoffee.inventory.access.application.port.`in`.command

/** 직원 한 명의 창고 소속 전체. [principalId]는 UUID 문자열이다. 형식은 처리할 때 검증한다 */
data class ReplaceWarehouseAccessCommand(
    val principalId: String?,
    val warehouseIds: List<Long>,
)
