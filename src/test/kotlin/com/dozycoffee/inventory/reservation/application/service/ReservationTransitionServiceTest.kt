package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.ReleaseInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import com.dozycoffee.inventory.reservation.application.port.out.ReservationEventRepository
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationExpiredException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationNotFoundException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ReservationTransitionServiceTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationEventRepository: ReservationEventRepository

    @Mock
    private lateinit var publisher: ReservationChangedEventPublisher

    @Mock
    private lateinit var releaseInventoryUseCase: ReleaseInventoryUseCase

    private lateinit var service: ReservationTransitionService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)

    @BeforeEach
    fun setUp() {
        service =
            ReservationTransitionService(
                reservationRepository,
                reservationEventRepository,
                publisher,
                releaseInventoryUseCase,
                DirectTransactionalOperator(),
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            )
    }

    private fun reservation(
        status: ReservationStatus = ReservationStatus.RESERVED,
        expiresAt: LocalDateTime? = now.plusMinutes(30),
        fulfilled: Int = 0,
    ): Reservation =
        Reservation.reconstitute(
            1L,
            10L,
            ReservationChannel.of("OMS"),
            ExternalOrderId.of("ORDER-1"),
            status,
            ReservationExpiry.reconstitute(expiresAt, now.plusHours(1)),
            if (status == ReservationStatus.CONFIRMED) now.minusMinutes(5) else null,
            IdempotencyKey.of("svc-oms-order-1"),
            RequesterService.of("svc-oms"),
            listOf(
                ReservationItem.reconstitute(
                    11L,
                    100L,
                    10,
                    listOf(
                        ReservationAllocation.reconstitute(21L, 5L, 4, fulfilled.coerceAtMost(4)),
                        ReservationAllocation.reconstitute(22L, 7L, 6, 0),
                    ),
                ),
                ReservationItem.reconstitute(12L, 101L, 3, listOf(ReservationAllocation.reconstitute(23L, 9L, 3, 0))),
            ),
        )

    private suspend fun found(reservation: Reservation) {
        whenever(reservationRepository.findById(1L)).thenReturn(reservation)
        whenever(reservationRepository.updateState(any(), any())).thenReturn(true)
        whenever(reservationEventRepository.save(any())).thenAnswer { it.getArgument<ReservationEvent>(0) }
    }

    private suspend fun verifyNothingWritten() {
        verifyBlocking(reservationRepository, never()) { updateState(any(), any()) }
        verifyBlocking(reservationEventRepository, never()) { save(any()) }
        verifyBlocking(publisher, never()) { publish(any()) }
        verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
    }

    private suspend fun verifyRecorded(
        eventType: ReservationEventType,
        changeType: ReservationChangeType,
    ) {
        val history = argumentCaptor<ReservationEvent>()
        verifyBlocking(reservationEventRepository) { save(history.capture()) }
        assertEquals(eventType, history.firstValue.eventType)
        assertEquals(1L, history.firstValue.reservationId)
        val published = argumentCaptor<ReservationChangedEvent>()
        verifyBlocking(publisher) { publish(published.capture()) }
        assertEquals(changeType, published.firstValue.changeType)
        assertEquals(1L, published.firstValue.reservationId)
        assertEquals(listOf(100L, 101L), published.firstValue.items.map { it.productId })
    }

    @Nested
    inner class `확정` {
        @Test
        fun `확정 전 예약을 확정하고 상태를 조건부로 저장하며 이력과 이벤트를 남긴다`() =
            runBlocking<Unit> {
                found(reservation())

                val result: ReservationResult = service.confirm(1L)

                assertEquals(ReservationStatus.CONFIRMED, result.status)
                assertEquals(null, result.expiresAt)
                verifyBlocking(reservationRepository) { updateState(any(), eq(ReservationStatus.RESERVED)) }
                verifyRecorded(ReservationEventType.CONFIRMED, ReservationChangeType.CONFIRMED)
                verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
            }

        @Test
        fun `이미 확정된 예약은 아무것도 쓰지 않고 현재 상태를 반환한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.CONFIRMED, null))

                assertEquals(ReservationStatus.CONFIRMED, service.confirm(1L).status)

                verifyNothingWritten()
            }

        @Test
        fun `없는 예약은 404이다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(null)

                assertThrows<ReservationNotFoundException> { service.confirm(1L) }
            }

        @Test
        fun `만료 시각이 지난 예약은 확정할 수 없고 아무것도 쓰지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(expiresAt = now.minusMinutes(1)))

                assertThrows<ReservationExpiredException> { service.confirm(1L) }

                verifyNothingWritten()
            }

        @Test
        fun `해제된 예약은 확정할 수 없다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.RELEASED, null))

                assertThrows<InvalidReservationStateException> { service.confirm(1L) }

                verifyNothingWritten()
            }

        @Test
        fun `저장에서 밀리면 새로 읽어 이미 확정된 것을 보고 멱등으로 성공한다`() =
            runBlocking<Unit> {
                whenever(
                    reservationRepository.findById(1L),
                ).thenReturn(reservation()).thenReturn(reservation(ReservationStatus.CONFIRMED, null))
                whenever(reservationRepository.updateState(any(), any())).thenReturn(false)

                assertEquals(ReservationStatus.CONFIRMED, service.confirm(1L).status)

                verifyBlocking(reservationRepository, times(2)) { findById(1L) }
                verifyBlocking(reservationEventRepository, never()) { save(any()) }
            }

        @Test
        fun `저장에서 밀려 새로 읽은 예약이 해제되어 있으면 409이다`() =
            runBlocking<Unit> {
                whenever(
                    reservationRepository.findById(1L),
                ).thenReturn(reservation()).thenReturn(reservation(ReservationStatus.RELEASED, null))
                whenever(reservationRepository.updateState(any(), any())).thenReturn(false)

                assertThrows<InvalidReservationStateException> { service.confirm(1L) }
            }

        @Test
        fun `계속 밀리면 3번 시도한 뒤 동시 변경 오류를 던진다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationRepository.updateState(any(), any())).thenReturn(false)

                assertThrows<ReservationChangedConcurrentlyException> { service.confirm(1L) }

                verifyBlocking(reservationRepository, times(3)) { findById(1L) }
            }
    }

    @Nested
    inner class `해제` {
        @Test
        fun `확정 전 예약을 해제하고 남은 예약 수량을 재고 행별로 되돌린다`() =
            runBlocking<Unit> {
                found(reservation())

                val result: ReservationResult = service.release(1L)

                assertEquals(ReservationStatus.RELEASED, result.status)
                val command = argumentCaptor<ReleaseInventoryCommand>()
                verifyBlocking(releaseInventoryUseCase) { release(command.capture()) }
                assertEquals(
                    setOf(ReleaseInventoryCommand.Item(5L, 4), ReleaseInventoryCommand.Item(7L, 6), ReleaseInventoryCommand.Item(9L, 3)),
                    command.firstValue.items.toSet(),
                )
                verifyRecorded(ReservationEventType.RELEASED, ReservationChangeType.RELEASED)
            }

        @Test
        fun `확정된 예약도 해제할 수 있다`() =
            runBlocking<Unit> {
                found(reservation(ReservationStatus.CONFIRMED, null))

                assertEquals(ReservationStatus.RELEASED, service.release(1L).status)

                verifyBlocking(reservationRepository) { updateState(any(), eq(ReservationStatus.CONFIRMED)) }
            }

        @Test
        fun `출고된 수량은 빼고 남은 수량만 되돌린다`() =
            runBlocking<Unit> {
                found(reservation(ReservationStatus.CONFIRMED, null, fulfilled = 3))

                service.release(1L)

                val command = argumentCaptor<ReleaseInventoryCommand>()
                verifyBlocking(releaseInventoryUseCase) { release(command.capture()) }
                assertEquals(ReleaseInventoryCommand.Item(5L, 1), command.firstValue.items.first { it.inventoryId == 5L })
            }

        @Test
        fun `이미 해제되었거나 만료된 예약은 아무것도 쓰지 않고 현재 상태를 반환한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(reservation(ReservationStatus.RELEASED, null))
                    .thenReturn(reservation(ReservationStatus.EXPIRED, now.minusMinutes(1)))

                assertEquals(ReservationStatus.RELEASED, service.release(1L).status)
                assertEquals(ReservationStatus.EXPIRED, service.release(1L).status)

                verifyNothingWritten()
            }

        @Test
        fun `출고 완료된 예약은 해제할 수 없다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.FULFILLED, null))

                assertThrows<InvalidReservationStateException> { service.release(1L) }

                verifyNothingWritten()
            }

        @Test
        fun `저장에서 밀리면 수량을 되돌리지 않는다`() =
            runBlocking<Unit> {
                whenever(
                    reservationRepository.findById(1L),
                ).thenReturn(reservation()).thenReturn(reservation(ReservationStatus.RELEASED, null))
                whenever(reservationRepository.updateState(any(), any())).thenReturn(false)

                assertEquals(ReservationStatus.RELEASED, service.release(1L).status)

                verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
            }
    }

    @Nested
    inner class `연장` {
        @Test
        fun `만료 시각을 늘리고 EXTENDED 이력을 남긴다`() =
            runBlocking<Unit> {
                found(reservation())

                val result: ReservationResult = service.extend(ExtendReservationCommand(1L, now.plusMinutes(50)))

                assertEquals(now.plusMinutes(50), result.expiresAt)
                verifyBlocking(reservationRepository) { updateState(any(), eq(ReservationStatus.RESERVED)) }
                verifyRecorded(ReservationEventType.EXTENDED, ReservationChangeType.EXTENDED)
                verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
            }

        @Test
        fun `나노초는 마이크로초로 잘라 처리한다`() =
            runBlocking<Unit> {
                found(reservation())

                val result: ReservationResult = service.extend(ExtendReservationCommand(1L, now.plusMinutes(50).plusNanos(123_456_789)))

                assertEquals(now.plusMinutes(50).plusNanos(123_456_000), result.expiresAt)
            }

        @Test
        fun `같은 만료 시각이면 아무것도 쓰지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation())

                assertEquals(now.plusMinutes(30), service.extend(ExtendReservationCommand(1L, now.plusMinutes(30))).expiresAt)

                verifyNothingWritten()
            }

        @Test
        fun `상한을 넘기거나 줄이려 하면 입력 오류이고 아무것도 쓰지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }

                listOf(now.plusHours(2), now.plusMinutes(10)).forEach { invalid ->
                    val e: InvalidDomainValueException =
                        assertThrows { runBlocking { service.extend(ExtendReservationCommand(1L, invalid)) } }
                    assertEquals(ReservationErrorCode.INVALID_RESERVATION_EXPIRY, e.errorCode)
                }
                verifyNothingWritten()
            }

        @Test
        fun `만료된 예약과 확정된 예약은 연장할 수 없다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(reservation(expiresAt = now.minusMinutes(1)))
                    .thenReturn(reservation(ReservationStatus.CONFIRMED, null))

                assertThrows<ReservationExpiredException> { service.extend(ExtendReservationCommand(1L, now.plusMinutes(40))) }
                assertThrows<InvalidReservationStateException> { service.extend(ExtendReservationCommand(1L, now.plusMinutes(40))) }
                verifyNothingWritten()
            }
    }

    @Nested
    inner class `만료` {
        @Test
        fun `만료 시각이 지난 확정 전 예약을 만료 처리하고 수량을 되돌린다`() =
            runBlocking<Unit> {
                found(reservation(expiresAt = now.minusMinutes(1)))

                assertEquals(true, service.expire(1L))

                verifyBlocking(reservationRepository) { updateState(any(), eq(ReservationStatus.RESERVED)) }
                val command = argumentCaptor<ReleaseInventoryCommand>()
                verifyBlocking(releaseInventoryUseCase) { release(command.capture()) }
                assertEquals(3, command.firstValue.items.size)
                verifyRecorded(ReservationEventType.EXPIRED, ReservationChangeType.EXPIRED)
            }

        @Test
        fun `없는 예약과 만료 전인 예약과 확정된 예약은 처리하지 않고 false다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(null)
                    .thenReturn(reservation(expiresAt = now.plusMinutes(1)))
                    .thenReturn(reservation(ReservationStatus.CONFIRMED, null))

                assertEquals(false, service.expire(1L))
                assertEquals(false, service.expire(1L))
                assertEquals(false, service.expire(1L))

                verifyNothingWritten()
            }

        @Test
        fun `확정이나 해제와 겹쳐 저장에서 밀리면 수량을 되돌리지 않고 false다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(expiresAt = now.minusMinutes(1)))
                whenever(reservationRepository.updateState(any(), any())).thenReturn(false)

                assertEquals(false, service.expire(1L))

                verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
                verifyBlocking(reservationEventRepository, never()) { save(any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }
    }
}
