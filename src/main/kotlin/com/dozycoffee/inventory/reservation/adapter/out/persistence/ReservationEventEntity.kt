package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.common.CreatedAuditEntity
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/** 변경하지 않는 이력이라 생성 정보만 가진다. `detail`은 JSON 컬럼이며 문자열로 주고받는다 */
@Table("reservation_event")
class ReservationEventEntity(
    @Id
    @Column("reservation_event_id")
    var reservationEventId: Long? = null,
    var reservationId: Long,
    var eventType: ReservationEventType,
    var detail: String?,
) : CreatedAuditEntity() {
    fun toDomain(): ReservationEvent =
        ReservationEvent.reconstitute(
            reservationEventId = checkNotNull(reservationEventId) { "저장된 예약 이력은 식별자가 있어야 한다" },
            reservationId = reservationId,
            eventType = eventType,
            detail = detail,
        )

    companion object {
        fun from(event: ReservationEvent): ReservationEventEntity =
            ReservationEventEntity(
                reservationEventId = event.reservationEventId,
                reservationId = event.reservationId,
                eventType = event.eventType,
                detail = event.detail,
            )
    }
}
