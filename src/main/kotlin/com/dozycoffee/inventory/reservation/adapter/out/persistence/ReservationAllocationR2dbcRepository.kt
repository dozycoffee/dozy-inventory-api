package com.dozycoffee.inventory.reservation.adapter.out.persistence

import kotlinx.coroutines.flow.Flow
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ReservationAllocationR2dbcRepository : CoroutineCrudRepository<ReservationAllocationEntity, Long> {
    fun findAllByReservationItemIdInOrderByInventoryIdAsc(reservationItemIds: Collection<Long>): Flow<ReservationAllocationEntity>
}
