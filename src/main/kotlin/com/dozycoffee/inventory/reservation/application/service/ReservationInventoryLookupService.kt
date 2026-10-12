package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.reservation.application.port.`in`.FindReservationsByInventoryUseCase
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReservationInventoryLookupService(
    private val reservationRepository: ReservationRepository,
) : FindReservationsByInventoryUseCase {
    @Transactional(readOnly = true)
    override suspend fun findUnconfirmedIds(inventoryIds: Set<Long>): List<Long> = find(inventoryIds, ReservationStatus.RESERVED)

    @Transactional(readOnly = true)
    override suspend fun findConfirmedIds(inventoryIds: Set<Long>): List<Long> = find(inventoryIds, ReservationStatus.CONFIRMED)

    private suspend fun find(
        inventoryIds: Set<Long>,
        status: ReservationStatus,
    ): List<Long> = if (inventoryIds.isEmpty()) emptyList() else reservationRepository.findIdsByInventoryIds(inventoryIds, status)
}
