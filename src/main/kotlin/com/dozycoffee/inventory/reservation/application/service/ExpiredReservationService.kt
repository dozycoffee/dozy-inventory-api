package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.reservation.application.port.`in`.FindExpiredReservationsUseCase
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
class ExpiredReservationService(
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
) : FindExpiredReservationsUseCase {
    @Transactional(readOnly = true)
    override suspend fun findExpiredIds(limit: Int): List<Long> {
        require(limit in 1..MAX_LIMIT) { "한 번에 가져올 개수는 1 이상 $MAX_LIMIT 이하여야 한다: $limit" }
        return reservationRepository.findExpiredIds(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS), limit)
    }
}

private const val MAX_LIMIT: Int = 1000
