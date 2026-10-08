package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.GetOutboundResultUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ShipInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult
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
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class ReservationFulfillmentServiceTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationEventRepository: ReservationEventRepository

    @Mock
    private lateinit var publisher: ReservationChangedEventPublisher

    @Mock
    private lateinit var shipInventoryUseCase: ShipInventoryUseCase

    @Mock
    private lateinit var getOutboundResultUseCase: GetOutboundResultUseCase

    private lateinit var service: ReservationFulfillmentService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 8, 12, 0)
    private val key: String = "wms-outbound-1"

    @BeforeEach
    fun setUp() {
        service =
            ReservationFulfillmentService(
                reservationRepository,
                reservationEventRepository,
                publisher,
                shipInventoryUseCase,
                getOutboundResultUseCase,
                DirectTransactionalOperator(),
            )
    }

    /** 상품 100은 재고 행 5(6개)와 7(4개)에, 상품 101은 재고 행 9(3개)에 할당된 예약 */
    private fun reservation(
        status: ReservationStatus = ReservationStatus.CONFIRMED,
        shipped: Map<Long, Int> = emptyMap(),
    ): Reservation =
        Reservation.reconstitute(
            1L,
            10L,
            ReservationChannel.of("OMS"),
            ExternalOrderId.of("ORDER-1"),
            status,
            ReservationExpiry.reconstitute(null, now.plusHours(1)),
            now.minusMinutes(5),
            IdempotencyKey.of("svc-oms-order-1"),
            RequesterService.of("svc-oms"),
            listOf(
                ReservationItem.reconstitute(
                    11L,
                    100L,
                    10,
                    listOf(
                        ReservationAllocation.reconstitute(21L, 5L, 6, shipped[5L] ?: 0),
                        ReservationAllocation.reconstitute(
                            22L,
                            7L,
                            4,
                            shipped[7L] ?: 0,
                        ),
                    ),
                ),
                ReservationItem.reconstitute(12L, 101L, 3, listOf(ReservationAllocation.reconstitute(23L, 9L, 3, shipped[9L] ?: 0))),
            ),
        )

    private fun command(
        vararg shipped: Pair<Long, Int>,
        key: String = this.key,
    ): FulfillReservationCommand =
        FulfillReservationCommand(1L, shipped.map { FulfillReservationCommand.Allocation(it.first, it.second) }, key, "svc-wms")

    private val full: Array<Pair<Long, Int>> = arrayOf(5L to 6, 7L to 4, 9L to 3)

    private suspend fun stubSuccess(reservation: Reservation = reservation()) {
        whenever(reservationRepository.findById(1L)).thenReturn(reservation)
        whenever(reservationRepository.updateFulfillment(any(), any())).thenReturn(true)
        whenever(shipInventoryUseCase.ship(any())).thenAnswer { invocation ->
            val shipCommand: ShipInventoryCommand = invocation.getArgument(0)
            ShipResult(
                shipCommand.items.map {
                    ShipResult.Item(
                        it.inventoryId,
                        if (it.shippedQuantity >
                            0
                        ) {
                            100 + it.inventoryId.toInt()
                        } else {
                            null
                        },
                    )
                },
            )
        }
        whenever(reservationEventRepository.save(any())).thenAnswer { it.getArgument<ReservationEvent>(0) }
    }

    private suspend fun verifyNothingWritten() {
        verifyBlocking(reservationRepository, never()) { updateFulfillment(any(), any()) }
        verifyBlocking(shipInventoryUseCase, never()) { ship(any()) }
        verifyBlocking(reservationEventRepository, never()) { save(any()) }
        verifyBlocking(publisher, never()) { publish(any()) }
    }

    @Nested
    inner class `출고 확정` {
        @Test
        fun `전량 출고하면 FULFILLED가 되고 재고 행별 결과와 처리 후 수량을 반환한다`() =
            runBlocking<Unit> {
                stubSuccess()

                val result: FulfillmentResult = service.fulfill(command(*full))

                assertEquals(ReservationStatus.FULFILLED, result.status)
                assertEquals(listOf(5L, 7L, 9L), result.allocations.map { it.inventoryId })
                assertEquals(listOf(6, 4, 3), result.allocations.map { it.shippedQuantity })
                assertEquals(listOf(0, 0, 0), result.allocations.map { it.shortageQuantity })
                assertEquals(listOf(105, 107, 109), result.allocations.map { it.quantityAfter })
                assertEquals(listOf(100L, 100L, 101L), result.allocations.map { it.productId })
            }

        @Test
        fun `상태를 조건부로 저장한 뒤 출고 수량과 결품을 재고 반영 명령으로 넘긴다`() =
            runBlocking<Unit> {
                stubSuccess()

                service.fulfill(command(5L to 4, 7L to 0, 9L to 3))

                verifyBlocking(reservationRepository) { updateFulfillment(any(), eq(ReservationStatus.CONFIRMED)) }
                val ship = argumentCaptor<ShipInventoryCommand>()
                verifyBlocking(shipInventoryUseCase) { ship(ship.capture()) }
                assertEquals(
                    listOf(ShipInventoryCommand.Item(5L, 4, 2), ShipInventoryCommand.Item(7L, 0, 4), ShipInventoryCommand.Item(9L, 3, 0)),
                    ship.firstValue.items,
                )
                assertEquals(key, ship.firstValue.idempotencyKey)
                assertEquals(1L, ship.firstValue.referenceId)
                assertEquals("svc-wms", ship.firstValue.requesterService)
            }

        @Test
        fun `결품이 있으면 출고하지 않은 행은 처리 후 수량이 null이다`() =
            runBlocking<Unit> {
                stubSuccess()

                val result: FulfillmentResult = service.fulfill(command(5L to 4, 7L to 0, 9L to 3))

                assertEquals(listOf(2, 4, 0), result.allocations.map { it.shortageQuantity })
                assertEquals(listOf(105, null, 109), result.allocations.map { it.quantityAfter })
            }

        @Test
        fun `FULFILLED 이력과 이벤트를 남기고 이력 상세에 결품을 담는다`() =
            runBlocking<Unit> {
                stubSuccess()

                service.fulfill(command(5L to 4, 7L to 0, 9L to 3))

                val history = argumentCaptor<ReservationEvent>()
                verifyBlocking(reservationEventRepository) { save(history.capture()) }
                assertEquals(ReservationEventType.FULFILLED, history.firstValue.eventType)
                assertEquals(1L, history.firstValue.reservationId)
                assertEquals(
                    """{"allocations":[{"inventoryId":5,"allocated":6,"shipped":4,"shortage":2},""" +
                        """{"inventoryId":7,"allocated":4,"shipped":0,"shortage":4},{"inventoryId":9,"allocated":3,"shipped":3,"shortage":0}]}""",
                    history.firstValue.detail,
                )
                val event = argumentCaptor<ReservationChangedEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(ReservationChangeType.FULFILLED, event.firstValue.changeType)
            }
    }

    @Nested
    inner class `입력과 상태 검증` {
        @Test
        fun `형식 오류는 아무것도 읽거나 쓰기 전에 거부한다`() =
            runBlocking<Unit> {
                val badKey: InvalidDomainValueException = assertThrows { runBlocking { service.fulfill(command(*full, key = " ")) } }
                val badRequester: InvalidDomainValueException =
                    assertThrows {
                        runBlocking {
                            service.fulfill(
                                FulfillReservationCommand(1L, listOf(FulfillReservationCommand.Allocation(5L, 1)), key, " "),
                            )
                        }
                    }

                assertEquals(CommonErrorCode.INVALID_IDEMPOTENCY_KEY, badKey.errorCode)
                assertEquals(CommonErrorCode.INVALID_REQUESTER_SERVICE, badRequester.errorCode)
                verifyBlocking(reservationRepository, never()) { findById(any()) }
            }

        @Test
        fun `명령은 비어 있거나 행이 중복되거나 음수이면 입력 오류다`() {
            val invalid: List<() -> FulfillReservationCommand> =
                listOf(
                    { FulfillReservationCommand(1L, emptyList(), key, "svc") },
                    { command(5L to 1, 5L to 2) },
                    { command(5L to -1) },
                    { command(0L to 1) },
                )
            invalid.forEach { build ->
                val e: InvalidDomainValueException = assertThrows { build() }
                assertEquals(ReservationErrorCode.INVALID_FULFILLMENT, e.errorCode)
            }
        }

        @Test
        fun `없는 예약은 404이다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(null)

                assertThrows<ReservationNotFoundException> { service.fulfill(command(*full)) }
            }

        @Test
        fun `할당 행이 빠지거나 수량이 넘치면 입력 오류이고 아무것도 쓰지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }

                listOf(arrayOf(5L to 6, 7L to 4), arrayOf(5L to 7, 7L to 4, 9L to 3)).forEach { shipped ->
                    val e: InvalidDomainValueException = assertThrows { runBlocking { service.fulfill(command(*shipped)) } }
                    assertEquals(ReservationErrorCode.INVALID_FULFILLMENT, e.errorCode)
                }
                verifyNothingWritten()
            }

        @Test
        fun `확정 전이거나 해제된 예약은 출고 확정할 수 없다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(reservation(ReservationStatus.RESERVED))
                    .thenReturn(reservation(ReservationStatus.RELEASED))

                assertThrows<InvalidReservationStateException> { service.fulfill(command(*full)) }
                assertThrows<InvalidReservationStateException> { service.fulfill(command(*full)) }
                verifyNothingWritten()
            }
    }

    @Nested
    inner class `재요청` {
        private val shippedFull: Map<Long, Int> = mapOf(5L to 4, 7L to 0, 9L to 3)

        @Test
        fun `같은 키와 같은 수량이면 새로 반영하지 않고 처음과 같은 결과를 반환한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.FULFILLED, shippedFull))
                whenever(getOutboundResultUseCase.getQuantitiesAfter(key, 1L)).thenReturn(mapOf(5L to 105, 9L to 109))

                val result: FulfillmentResult = service.fulfill(command(5L to 4, 7L to 0, 9L to 3))

                assertEquals(listOf(105, null, 109), result.allocations.map { it.quantityAfter })
                assertEquals(listOf(4, 0, 3), result.allocations.map { it.shippedQuantity })
                verifyNothingWritten()
            }

        @Test
        fun `출고한 행의 이력이 같은 키에 없으면 다른 키의 요청이라 409다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.FULFILLED, shippedFull))
                whenever(getOutboundResultUseCase.getQuantitiesAfter("other-key", 1L)).thenReturn(emptyMap())

                assertThrows<IdempotencyKeyConflictException> { service.fulfill(command(5L to 4, 7L to 0, 9L to 3, key = "other-key")) }
            }

        @Test
        fun `모두 출고하지 못한 예약의 재요청은 이력이 없어도 같은 결과다`() =
            runBlocking<Unit> {
                val allShort: Map<Long, Int> = mapOf(5L to 0, 7L to 0, 9L to 0)
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.FULFILLED, allShort))
                whenever(getOutboundResultUseCase.getQuantitiesAfter(key, 1L)).thenReturn(emptyMap())

                val result: FulfillmentResult = service.fulfill(command(5L to 0, 7L to 0, 9L to 0))

                assertEquals(listOf(null, null, null), result.allocations.map { it.quantityAfter })
            }

        @Test
        fun `이미 출고 확정된 예약에 다른 수량이 오면 상태 오류다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation(ReservationStatus.FULFILLED, shippedFull))

                assertThrows<InvalidReservationStateException> { service.fulfill(command(*full)) }
                verifyNothingWritten()
            }

        @Test
        fun `저장에서 밀리면 새로 읽어 이미 출고 확정된 것을 보고 같은 결과로 성공한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(reservation())
                    .thenReturn(reservation(ReservationStatus.FULFILLED, shippedFull))
                whenever(reservationRepository.updateFulfillment(any(), any())).thenReturn(false)
                whenever(getOutboundResultUseCase.getQuantitiesAfter(key, 1L)).thenReturn(mapOf(5L to 105, 9L to 109))

                val result: FulfillmentResult = service.fulfill(command(5L to 4, 7L to 0, 9L to 3))

                assertEquals(ReservationStatus.FULFILLED, result.status)
                verifyBlocking(reservationRepository, times(2)) { findById(1L) }
                verifyBlocking(shipInventoryUseCase, never()) { ship(any()) }
            }

        @Test
        fun `저장에서 밀린 사이 해제되었으면 상태 오류다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L))
                    .thenReturn(reservation())
                    .thenReturn(reservation(ReservationStatus.RELEASED))
                whenever(reservationRepository.updateFulfillment(any(), any())).thenReturn(false)

                assertThrows<InvalidReservationStateException> { service.fulfill(command(*full)) }
            }

        @Test
        fun `계속 밀리면 3번 시도한 뒤 동시 변경 오류를 던지고 재고를 건드리지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationRepository.updateFulfillment(any(), any())).thenReturn(false)

                assertThrows<ReservationChangedConcurrentlyException> { service.fulfill(command(*full)) }

                verifyBlocking(reservationRepository, times(3)) { findById(1L) }
                verifyBlocking(shipInventoryUseCase, never()) { ship(any()) }
            }
    }

    @Nested
    inner class `재고 반영 실패` {
        @Test
        fun `재고 반영이 실패하면 이력과 이벤트를 남기지 않고 예외가 그대로 나간다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(reservation())
                whenever(reservationRepository.updateFulfillment(any(), any())).thenReturn(true)
                whenever(shipInventoryUseCase.ship(any())).thenThrow(InsufficientReservedQuantityException())

                assertThrows<InsufficientReservedQuantityException> { service.fulfill(command(*full)) }

                verifyBlocking(reservationEventRepository, never()) { save(any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }
    }
}
