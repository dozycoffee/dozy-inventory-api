package com.dozycoffee.inventory.reservation.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ReservationR2dbcRepository : CoroutineCrudRepository<ReservationEntity, Long> {
    suspend fun findByIdempotencyKey(idempotencyKey: String): ReservationEntity?
}
