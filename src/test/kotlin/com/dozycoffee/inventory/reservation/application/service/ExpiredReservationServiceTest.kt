package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ExpiredReservationServiceTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    private lateinit var service: ExpiredReservationService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)

    @BeforeEach
    fun setUp() {
        service =
            ExpiredReservationService(
                reservationRepository,
                Clock.fixed(now.plusNanos(123_456_789).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            )
    }

    @Test
    fun `현재 시각을 마이크로초로 잘라 만료 대상을 조회한다`() =
        runBlocking<Unit> {
            whenever(reservationRepository.findExpiredIds(now.plusNanos(123_456_000), 100)).thenReturn(listOf(3L, 1L))

            assertEquals(listOf(3L, 1L), service.findExpiredIds(100))
        }

    @Test
    fun `개수는 1 이상 1000 이하여야 한다`() =
        runBlocking<Unit> {
            assertThrows<IllegalArgumentException> { service.findExpiredIds(0) }
            assertThrows<IllegalArgumentException> { service.findExpiredIds(1001) }
        }
}
