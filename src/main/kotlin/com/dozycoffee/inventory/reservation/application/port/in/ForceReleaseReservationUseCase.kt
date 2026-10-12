package com.dozycoffee.inventory.reservation.application.port.`in`

interface ForceReleaseReservationUseCase {
    /**
     * 확정 전(`RESERVED`) 예약 하나를 시스템이 강제로 해제하고 예약 수량을 되돌린다(유통기한 경과 등). 해제했으면 true를 반환한다.
     * 이미 확정·해제·만료·출고되었거나 없는 예약이면 아무것도 하지 않고 false를 반환한다. 확정된 예약은 강제 해제하지 않는다.
     * [reason]은 이력과 `RESERVATION_FORCE_RELEASED` 이벤트에 남는 사유 코드이며 영문 대문자와 숫자와 밑줄 50자 이하다(예: `LOT_EXPIRED`).
     * 호출하는 쪽이 예약 하나씩 부르므로 한 건의 실패가 다른 예약에 영향을 주지 않는다.
     */
    suspend fun forceRelease(
        reservationId: Long,
        reason: String,
    ): Boolean
}
