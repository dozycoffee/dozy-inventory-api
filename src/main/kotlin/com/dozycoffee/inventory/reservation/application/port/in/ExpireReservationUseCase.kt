package com.dozycoffee.inventory.reservation.application.port.`in`

interface ExpireReservationUseCase {
    /**
     * 만료 시각이 지난 확정 전 예약 하나를 만료 처리하고 예약 수량을 되돌린다. 처리했으면 true를 반환한다.
     * 이미 확정·해제되었거나 아직 만료 전이거나 없는 예약이면 아무것도 하지 않고 false를 반환한다.
     * 스케줄러가 만료 대상을 하나씩 호출하므로 한 건의 실패가 다른 예약에 영향을 주지 않는다.
     */
    suspend fun expire(reservationId: Long): Boolean
}
