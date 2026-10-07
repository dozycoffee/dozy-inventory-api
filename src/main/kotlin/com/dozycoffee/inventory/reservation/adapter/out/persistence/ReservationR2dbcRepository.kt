package com.dozycoffee.inventory.reservation.adapter.out.persistence

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime

interface ReservationR2dbcRepository : CoroutineCrudRepository<ReservationEntity, Long> {
    suspend fun findByIdempotencyKey(idempotencyKey: String): ReservationEntity?

    @Query(
        """
        SELECT COUNT(*) FROM reservation
         WHERE channel = :channel AND external_order_id = :externalOrderId
           AND (status = 'CONFIRMED' OR (status = 'RESERVED' AND expires_at > :now))
        """,
    )
    suspend fun countActive(
        channel: String,
        externalOrderId: String,
        now: LocalDateTime,
    ): Long
}
