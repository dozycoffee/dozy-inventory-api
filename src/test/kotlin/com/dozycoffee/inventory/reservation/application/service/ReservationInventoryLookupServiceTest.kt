package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ReservationInventoryLookupServiceTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    private lateinit var service: ReservationInventoryLookupService

    @BeforeEach
    fun setUp() {
        service = ReservationInventoryLookupService(reservationRepository)
    }

    @Test
    fun `확정 전 예약은 RESERVED로 확정된 예약은 CONFIRMED로 조회한다`() =
        runBlocking<Unit> {
            whenever(reservationRepository.findIdsByInventoryIds(setOf(5L, 7L), ReservationStatus.RESERVED)).thenReturn(listOf(1L, 2L))
            whenever(reservationRepository.findIdsByInventoryIds(setOf(5L, 7L), ReservationStatus.CONFIRMED)).thenReturn(listOf(3L))

            assertEquals(listOf(1L, 2L), service.findUnconfirmedIds(setOf(5L, 7L)))
            assertEquals(listOf(3L), service.findConfirmedIds(setOf(5L, 7L)))
        }

    @Test
    fun `재고 행이 없으면 저장소를 조회하지 않고 빈 목록을 준다`() =
        runBlocking<Unit> {
            assertEquals(emptyList<Long>(), service.findUnconfirmedIds(emptySet()))
            assertEquals(emptyList<Long>(), service.findConfirmedIds(emptySet()))

            verifyNoInteractions(reservationRepository)
        }
}
