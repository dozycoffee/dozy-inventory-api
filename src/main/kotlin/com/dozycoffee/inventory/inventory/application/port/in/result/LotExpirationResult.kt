package com.dozycoffee.inventory.inventory.application.port.`in`.result

import java.time.LocalDate

/**
 * Lot 하나를 처리한 결과. [becameExpired]는 이번에 경과로 바뀌었는지이고 [expired]는 처리 후 경과 상태인지다.
 * [transfers]는 폐기 예정 행으로 전환한 행별 내역이며 전환할 수량이 없었으면 비어 있다.
 */
data class LotExpirationResult(
    val lotId: Long,
    val productId: Long,
    val lotNumber: String,
    val expirationDate: LocalDate?,
    val statusChanged: Boolean,
    val becameExpired: Boolean,
    val expired: Boolean,
    val transfers: List<Transfer>,
) {
    /**
     * [quantity]는 전환한 수량, [remainingReservedQuantity]는 확정된 예약이 잡고 있어 원래 행에 남은 수량이다.
     * 전환 후 행은 [targetInventoryId]다.
     */
    data class Transfer(
        val warehouseId: Long,
        val sourceInventoryId: Long,
        val targetInventoryId: Long,
        val quantity: Int,
        val remainingReservedQuantity: Int,
    )
}
