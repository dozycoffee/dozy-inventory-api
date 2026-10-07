package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode

/** [warehouseIds]가 null이면 모든 창고를 조회한다. 중복 ID는 합쳐진다 */
data class GetAvailabilityQuery(
    val productIds: Set<Long>,
    val warehouseIds: Set<Long>?,
) {
    init {
        if (productIds.isEmpty() || productIds.size > MAX_IDS || productIds.any { it < 1 }) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_AVAILABILITY_QUERY)
        }
        if (warehouseIds != null && (warehouseIds.isEmpty() || warehouseIds.size > MAX_IDS || warehouseIds.any { it < 1 })) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_AVAILABILITY_QUERY)
        }
    }

    companion object {
        const val MAX_IDS: Int = 100
    }
}
