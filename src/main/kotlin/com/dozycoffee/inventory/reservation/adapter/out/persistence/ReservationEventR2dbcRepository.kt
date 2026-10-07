package com.dozycoffee.inventory.reservation.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ReservationEventR2dbcRepository : CoroutineCrudRepository<ReservationEventEntity, Long> {
    fun findAllByReservationIdOrderByReservationEventIdAsc(reservationId: Long): Flow<ReservationEventEntity>
}
