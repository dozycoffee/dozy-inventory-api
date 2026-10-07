package com.dozycoffee.inventory.reservation.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ReservationItemR2dbcRepository : CoroutineCrudRepository<ReservationItemEntity, Long> {
    fun findAllByReservationIdOrderByReservationItemIdAsc(reservationId: Long): Flow<ReservationItemEntity>
}
