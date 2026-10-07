package com.dozycoffee.inventory.reservation.domain.model

import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType

/** 예약에 일어난 일의 이력. 만든 뒤에는 바뀌지 않는다. [detail]은 사유, 변경 전후 만료 시각 같은 부가 정보의 JSON 문자열이다 */
class ReservationEvent private constructor(
    val reservationEventId: Long?,
    val reservationId: Long,
    val eventType: ReservationEventType,
    val detail: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReservationEvent) return false
        return reservationEventId != null && reservationEventId == other.reservationEventId
    }

    override fun hashCode(): Int = reservationEventId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            reservationId: Long,
            eventType: ReservationEventType,
            detail: String?,
        ): ReservationEvent = ReservationEvent(null, reservationId, eventType, detail)

        fun reconstitute(
            reservationEventId: Long,
            reservationId: Long,
            eventType: ReservationEventType,
            detail: String?,
        ): ReservationEvent = ReservationEvent(reservationEventId, reservationId, eventType, detail)
    }
}
