package com.dozycoffee.inventory.reservation.adapter.`in`.scheduler

import com.dozycoffee.inventory.reservation.application.port.`in`.ExpireReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FindExpiredReservationsUseCase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ReservationExpirySchedulerTest {
    @Mock
    private lateinit var findExpiredReservationsUseCase: FindExpiredReservationsUseCase

    @Mock
    private lateinit var expireReservationUseCase: ExpireReservationUseCase

    private lateinit var scheduler: ReservationExpiryScheduler

    @BeforeEach
    fun setUp() {
        scheduler = ReservationExpiryScheduler(findExpiredReservationsUseCase, expireReservationUseCase, 3)
    }

    @Nested
    inner class `만료 처리` {
        @Test
        fun `만료 대상이 없으면 아무것도 하지 않는다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenReturn(emptyList())

                scheduler.scan()

                verifyBlocking(expireReservationUseCase, never()) { expire(any()) }
            }

        @Test
        fun `만료 대상을 예약 하나씩 UseCase에 넘긴다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenReturn(listOf(1L, 2L))
                whenever(expireReservationUseCase.expire(any())).thenReturn(true)

                scheduler.scan()

                verifyBlocking(expireReservationUseCase) { expire(1L) }
                verifyBlocking(expireReservationUseCase) { expire(2L) }
                verifyBlocking(findExpiredReservationsUseCase, times(1)) { findExpiredIds(3) }
            }

        @Test
        fun `한 건이 실패해도 나머지를 계속 처리한다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenReturn(listOf(1L, 2L, 3L)).thenReturn(emptyList())
                whenever(expireReservationUseCase.expire(1L)).thenThrow(IllegalStateException("boom"))
                whenever(expireReservationUseCase.expire(2L)).thenReturn(true)
                whenever(expireReservationUseCase.expire(3L)).thenReturn(false)

                scheduler.scan()

                verifyBlocking(expireReservationUseCase) { expire(2L) }
                verifyBlocking(expireReservationUseCase) { expire(3L) }
            }

        @Test
        fun `묶음이 가득 차고 처리한 것이 있으면 다음 묶음을 이어서 가져온다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3))
                    .thenReturn(listOf(1L, 2L, 3L))
                    .thenReturn(listOf(4L))
                whenever(expireReservationUseCase.expire(any())).thenReturn(true)

                scheduler.scan()

                verifyBlocking(findExpiredReservationsUseCase, times(2)) { findExpiredIds(3) }
                verifyBlocking(expireReservationUseCase) { expire(4L) }
            }

        @Test
        fun `묶음이 가득 찼어도 하나도 처리하지 못했으면 같은 대상을 반복하지 않고 멈춘다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenReturn(listOf(1L, 2L, 3L))
                whenever(expireReservationUseCase.expire(any())).thenReturn(false)

                scheduler.scan()

                verifyBlocking(findExpiredReservationsUseCase, times(1)) { findExpiredIds(3) }
            }

        @Test
        fun `한 번에 처리하는 묶음은 최대 10개다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenReturn(listOf(1L, 2L, 3L))
                whenever(expireReservationUseCase.expire(any())).thenReturn(true)

                scheduler.scan()

                verifyBlocking(findExpiredReservationsUseCase, times(10)) { findExpiredIds(3) }
            }
    }

    @Nested
    inner class `오류` {
        @Test
        fun `대상 조회가 실패해도 예외를 밖으로 던지지 않는다`() =
            runBlocking<Unit> {
                whenever(findExpiredReservationsUseCase.findExpiredIds(3)).thenThrow(IllegalStateException("db down"))

                scheduler.scan()

                verifyBlocking(expireReservationUseCase, never()) { expire(any()) }
            }
    }
}
