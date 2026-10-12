package com.dozycoffee.inventory.inventory.application.port.out

import java.time.LocalDate

/**
 * 유통기한이 지난 Lot을 처리했음을 WMS와 사람에게 알리는 이벤트(ADR-0027). WMS는 [transfers]로 폐기 신청을 만들고,
 * [confirmedReservationIds]는 수량을 잡고 있어 건너뛴 확정 예약이라 사람이 확인한다.
 * [Transfer.sourceInventoryId]는 정상 품질 행, [Transfer.disposalInventoryId]는 전환 후 폐기 예정 행이다.
 */
data class LotExpiredEvent(
    val lotId: Long,
    val productId: Long,
    val lotNumber: String,
    val expirationDate: LocalDate?,
    val transfers: List<Transfer>,
    val confirmedReservationIds: List<Long>,
) {
    data class Transfer(
        val warehouseId: Long,
        val sourceInventoryId: Long,
        val disposalInventoryId: Long,
        val quantity: Int,
    )
}
