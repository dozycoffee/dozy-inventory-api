package com.dozycoffee.inventory.reservation.application.port.`in`

interface FindExpiredReservationsUseCase {
    /** 만료 시각이 지난 확정 전 예약의 ID를 만료 시각이 이른 순으로 최대 [limit]개 반환한다. 만료 처리는 [ExpireReservationUseCase]로 하나씩 한다 */
    suspend fun findExpiredIds(limit: Int): List<Long>
}
