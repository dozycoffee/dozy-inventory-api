package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.reservation.application.port.out.ReservationEventRepository
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component

@Component
class ReservationEventPersistenceAdapter(
    private val reservationEventR2dbcRepository: ReservationEventR2dbcRepository,
) : ReservationEventRepository {
    override suspend fun save(event: ReservationEvent): ReservationEvent {
        check(event.reservationEventId == null) { "예약 이력은 변경할 수 없어 새로 저장만 할 수 있다" }
        return reservationEventR2dbcRepository.save(ReservationEventEntity.from(event)).toDomain()
    }

    override suspend fun findAllByReservationId(reservationId: Long): List<ReservationEvent> =
        reservationEventR2dbcRepository.findAllByReservationIdOrderByReservationEventIdAsc(reservationId).toList().map { it.toDomain() }
}
