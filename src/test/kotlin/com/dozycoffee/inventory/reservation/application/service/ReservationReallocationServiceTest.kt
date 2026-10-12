package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryLotsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.HoldInventoryAllocationUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ReleaseInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult
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
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class ReservationReallocationServiceTest {
    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationEventRepository: ReservationEventRepository

    @Mock
    private lateinit var publisher: ReservationChangedEventPublisher

    @Mock
    private lateinit var allocateInventoryUseCase: AllocateInventoryUseCase

    @Mock
    private lateinit var releaseInventoryUseCase: ReleaseInventoryUseCase

    @Mock
    private lateinit var holdInventoryAllocationUseCase: HoldInventoryAllocationUseCase

    @Mock
    private lateinit var getInventoryLotsUseCase: GetInventoryLotsUseCase

    private lateinit var service: ReservationReallocationService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 12, 12, 0)
    private val key: String = "wms-realloc-1"

    @BeforeEach
    fun setUp() {
        service =
            ReservationReallocationService(
                reservationRepository,
                reservationEventRepository,
                publisher,
                allocateInventoryUseCase,
                releaseInventoryUseCase,
                holdInventoryAllocationUseCase,
                getInventoryLotsUseCase,
                DirectTransactionalOperator(),
            )
    }

    /** 상품 100은 재고 행 5(6개)와 7(4개)에, 상품 101은 재고 행 9(3개)에 할당된 예약 */
    private fun reservation(status: ReservationStatus = ReservationStatus.CONFIRMED): Reservation =
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
                    listOf(ReservationAllocation.reconstitute(21L, 5L, 6, 0), ReservationAllocation.reconstitute(22L, 7L, 4, 0)),
                ),
                ReservationItem.reconstitute(12L, 101L, 3, listOf(ReservationAllocation.reconstitute(23L, 9L, 3, 0))),
            ),
        )

    private fun command(
        vararg shortages: Pair<Long, Int>,
        key: String = this.key,
    ): ReallocateReservationCommand =
        ReallocateReservationCommand(1L, shortages.map { ReallocateReservationCommand.Shortage(it.first, it.second) }, key)

    private fun lots(): List<InventoryLotResult> =
        listOf(5L, 7L, 9L, 11L).map { InventoryLotResult(it, it + 100, "LOT-$it", LocalDate.of(2027, 1, 1)) }

    private fun allocated(
        productId: Long,
        inventoryId: Long,
        quantity: Int,
    ): AllocationResult =
        AllocationResult(
            listOf(
                AllocationResult.ItemAllocation(
                    productId,
                    listOf(
                        AllocationResult.LotAllocation(
                            inventoryId,
                            inventoryId + 100,
                            null,
                            quantity,
                        ),
                    ),
                ),
            ),
        )

    private suspend fun stubReallocation(
        shortageProduct: Long = 100L,
        replacement: Long = 11L,
        quantity: Int = 2,
        stubAllocate: Boolean = true,
        stubLots: Boolean = true,
        stubSave: Boolean = true,
        stubLock: Boolean = true,
    ) {
        whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
        whenever(reservationRepository.updateState(any(), eq(ReservationStatus.CONFIRMED))).thenReturn(true)
        if (stubLock) whenever(reservationRepository.lockAllocations(any(), any())).thenReturn(true)
        if (stubAllocate) whenever(allocateInventoryUseCase.allocate(any())).thenReturn(allocated(shortageProduct, replacement, quantity))
        if (stubSave) whenever(reservationRepository.updateAllocations(any(), any())).thenReturn(true)
        if (stubLots) whenever(getInventoryLotsUseCase.getLots(any())).thenReturn(lots())
    }

    @Nested
    inner class `재할당` {
        @Test
        fun `부족 행을 보류하고 대체 Lot을 잡은 뒤 부족 수량을 되돌리고 할당을 저장한다`() =
            runBlocking<Unit> {
                stubReallocation()

                val result: ReallocationResult = service.reallocate(command(5L to 2))

                val order: InOrder =
                    inOrder(holdInventoryAllocationUseCase, reservationRepository, allocateInventoryUseCase, releaseInventoryUseCase)
                order.verify(holdInventoryAllocationUseCase).hold(setOf(5L), "피킹 불가 보고: 예약 1")
                order.verify(reservationRepository).updateState(any(), eq(ReservationStatus.CONFIRMED))
                order
                    .verify(
                        allocateInventoryUseCase,
                    ).allocate(AllocateInventoryCommand(10L, listOf(AllocateInventoryCommand.Item(100L, 2))))
                order.verify(releaseInventoryUseCase).release(ReleaseInventoryCommand(listOf(ReleaseInventoryCommand.Item(5L, 2))))
                order.verify(reservationRepository).updateAllocations(any(), eq(mapOf(21L to 6, 22L to 4, 23L to 3)))
                assertEquals(listOf(5L), result.heldInventoryIds)
                assertEquals(
                    listOf(5L to 4, 7L to 4, 11L to 2),
                    result.items
                        .first()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                )
                assertEquals(
                    "LOT-11",
                    result.items
                        .first()
                        .allocations
                        .last()
                        .lotNumber,
                )
            }

        @Test
        fun `이력은 멱등 키와 부족 내역과 새 할당을 담고 REALLOCATED 이벤트를 발행한다`() =
            runBlocking<Unit> {
                stubReallocation()

                service.reallocate(command(5L to 2))

                val event = argumentCaptor<ReservationEvent>()
                verifyBlocking(reservationEventRepository) { save(event.capture()) }
                assertEquals(ReservationEventType.REALLOCATED, event.firstValue.eventType)
                assertEquals(key, event.firstValue.idempotencyKey)
                assertEquals(
                    """{"shortages":"5:2","allocations":[""" +
                        """{"productId":100,"inventoryId":5,"quantity":4},{"productId":100,"inventoryId":7,"quantity":4},""" +
                        """{"productId":100,"inventoryId":11,"quantity":2}]}""",
                    event.firstValue.detail,
                )
                val published = argumentCaptor<ReservationChangedEvent>()
                verifyBlocking(publisher) { publish(published.capture()) }
                assertEquals(ReservationChangeType.REALLOCATED, published.firstValue.changeType)
                assertEquals(
                    listOf(5L to 4, 7L to 4, 11L to 2),
                    published.firstValue.items
                        .first()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                )
            }

        @Test
        fun `여러 상품의 부족을 한 번에 보내면 상품별로 대체 Lot을 요청한다`() =
            runBlocking<Unit> {
                stubReallocation(stubAllocate = false, stubLots = false)
                whenever(allocateInventoryUseCase.allocate(any())).thenReturn(
                    AllocationResult(
                        listOf(
                            AllocationResult.ItemAllocation(100L, listOf(AllocationResult.LotAllocation(11L, 111L, null, 3))),
                            AllocationResult.ItemAllocation(101L, listOf(AllocationResult.LotAllocation(12L, 112L, null, 3))),
                        ),
                    ),
                )
                whenever(getInventoryLotsUseCase.getLots(any())).thenReturn(lots() + InventoryLotResult(12L, 112L, "LOT-12", null))

                val result: ReallocationResult = service.reallocate(command(5L to 1, 7L to 2, 9L to 3))

                verifyBlocking(allocateInventoryUseCase) {
                    allocate(
                        AllocateInventoryCommand(
                            10L,
                            listOf(AllocateInventoryCommand.Item(100L, 3), AllocateInventoryCommand.Item(101L, 3)),
                        ),
                    )
                }
                verifyBlocking(holdInventoryAllocationUseCase) { hold(setOf(5L, 7L, 9L), "피킹 불가 보고: 예약 1") }
                assertEquals(listOf(5L, 7L, 9L), result.heldInventoryIds)
                assertEquals(listOf(100L, 101L), result.items.map { it.productId })
            }
    }

    @Nested
    inner class `멱등` {
        private fun previous(detail: String = """{"shortages":"5:2","allocations":[]}"""): ReservationEvent =
            ReservationEvent.reconstitute(1L, 1L, ReservationEventType.REALLOCATED, detail, key)

        @Test
        fun `같은 키와 같은 부족 내역이면 새로 반영하지 않고 현재 할당으로 결과를 만든다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationEventRepository.findByIdempotencyKey(1L, key)).thenReturn(previous())
                whenever(getInventoryLotsUseCase.getLots(any())).thenReturn(lots())

                val result: ReallocationResult = service.reallocate(command(5L to 2))

                assertEquals(listOf(5L), result.heldInventoryIds)
                // 이력을 먼저 읽고 예약을 나중에 읽어야 낡은 할당으로 응답하지 않는다
                inOrder(reservationEventRepository, reservationRepository).apply {
                    verifyBlocking(reservationEventRepository) { findByIdempotencyKey(1L, key) }
                    verifyBlocking(reservationRepository) { findById(1L) }
                }
                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
                verifyBlocking(allocateInventoryUseCase, never()) { allocate(any()) }
                verifyBlocking(reservationRepository, never()) { updateAllocations(any(), any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }

        @Test
        fun `검증하는 사이 같은 키의 요청이 끝나 할당이 바뀌었으면 입력 오류가 아니라 재요청으로 본다`() =
            runBlocking<Unit> {
                // 처음 이력을 읽을 때는 없었는데 예약을 읽어 보니 이미 5번 행의 할당이 1로 줄었고, 다시 이력을 읽으면 있다
                whenever(
                    reservationEventRepository.findByIdempotencyKey(1L, key),
                ).thenReturn(null, previous("""{"shortages":"5:7","allocations":[]}"""))
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(getInventoryLotsUseCase.getLots(any())).thenReturn(lots())

                val result: ReallocationResult = service.reallocate(command(5L to 7))

                assertEquals(listOf(5L), result.heldInventoryIds)
                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }

        @Test
        fun `부족 내역의 순서가 달라도 같은 요청으로 본다`() =
            runBlocking<Unit> {
                val detail = """{"shortages":"5:1,7:2","allocations":[]}"""
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationEventRepository.findByIdempotencyKey(1L, key)).thenReturn(previous(detail))
                whenever(getInventoryLotsUseCase.getLots(any())).thenReturn(lots())

                service.reallocate(command(7L to 2, 5L to 1))

                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }

        @Test
        fun `같은 키에 다른 부족 내역이면 409이고 보류하지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationEventRepository.findByIdempotencyKey(1L, key)).thenReturn(previous())

                assertThrows<IdempotencyKeyConflictException> { service.reallocate(command(5L to 3)) }
                assertThrows<IdempotencyKeyConflictException> { service.reallocate(command(5L to 2, 7L to 1)) }

                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }
    }

    @Nested
    inner class `요청 검증` {
        @Test
        fun `예약이 없으면 404이고 보류하지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenReturn(null)

                assertThrows<ReservationNotFoundException> { service.reallocate(command(5L to 2)) }

                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }

        @Test
        fun `확정이 아닌 예약은 409이고 보류하지 않는다`() =
            runBlocking<Unit> {
                ReservationStatus.entries.filter { it != ReservationStatus.CONFIRMED }.forEach { status ->
                    whenever(reservationRepository.findById(1L)).thenAnswer { reservation(status) }

                    assertThrows<InvalidReservationStateException> { service.reallocate(command(5L to 2)) }
                }

                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }

        @Test
        fun `예약에 없는 행이거나 수량이 할당을 넘으면 400이고 보류하지 않는다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }

                listOf(command(99L to 1), command(5L to 7)).forEach { invalid ->
                    val e: InvalidDomainValueException = assertThrows { service.reallocate(invalid) }
                    assertEquals(ReservationErrorCode.INVALID_REALLOCATION, e.errorCode)
                }

                verifyBlocking(holdInventoryAllocationUseCase, never()) { hold(any(), any()) }
            }

        @Test
        fun `부족 내역이 잘못된 Command는 만들 수 없다`() {
            listOf(
                { ReallocateReservationCommand(1L, emptyList(), key) },
                { command(5L to 0) },
                { command(0L to 1) },
                { command(5L to 1, 5L to 1) },
            ).forEach { build ->
                val e: InvalidDomainValueException = assertThrows { build() }
                assertEquals(ReservationErrorCode.INVALID_REALLOCATION, e.errorCode)
            }
        }
    }

    @Nested
    inner class `실패와 재시도` {
        @Test
        fun `대체 재고가 모자라면 409를 던지지만 보류는 이미 확정되어 있다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationRepository.updateState(any(), eq(ReservationStatus.CONFIRMED))).thenReturn(true)
                whenever(reservationRepository.lockAllocations(any(), any())).thenReturn(true)
                whenever(allocateInventoryUseCase.allocate(any())).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.reallocate(command(5L to 2)) }

                verifyBlocking(holdInventoryAllocationUseCase) { hold(setOf(5L), "피킹 불가 보고: 예약 1") }
                verifyBlocking(releaseInventoryUseCase, never()) { release(any()) }
                verifyBlocking(reservationRepository, never()) { updateAllocations(any(), any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }

        @Test
        fun `할당 경합에 밀리면 보류는 한 번만 하고 새 트랜잭션에서 다시 시도한다`() =
            runBlocking<Unit> {
                stubReallocation(stubAllocate = false)
                whenever(
                    allocateInventoryUseCase.allocate(any()),
                ).thenThrow(AllocationConflictException()).thenReturn(allocated(100L, 11L, 2))

                val result: ReallocationResult = service.reallocate(command(5L to 2))

                assertEquals(listOf(5L), result.heldInventoryIds)
                verifyBlocking(holdInventoryAllocationUseCase, times(1)) { hold(any(), any()) }
                verifyBlocking(allocateInventoryUseCase, times(2)) { allocate(any()) }
            }

        @Test
        fun `예약 행이나 할당이 그 사이 바뀌어 저장이 밀리면 다시 읽어 판정한다`() =
            runBlocking<Unit> {
                stubReallocation(stubSave = false)
                whenever(reservationRepository.updateAllocations(any(), any())).thenReturn(false, true)

                service.reallocate(command(5L to 2))

                verifyBlocking(reservationRepository, times(3)) { findById(1L) }
                verifyBlocking(reservationRepository, times(2)) { updateAllocations(any(), any()) }
            }

        @Test
        fun `읽은 할당이 낡았으면 재고 행을 건드리지 않고 다시 읽는다`() =
            runBlocking<Unit> {
                stubReallocation(stubSave = false, stubLock = false)
                whenever(reservationRepository.lockAllocations(any(), any())).thenReturn(false, true)
                whenever(reservationRepository.updateAllocations(any(), any())).thenReturn(true)

                service.reallocate(command(5L to 2))

                verifyBlocking(allocateInventoryUseCase, times(1)) { allocate(any()) }
                verifyBlocking(releaseInventoryUseCase, times(1)) { release(any()) }
            }

        @Test
        fun `계속 겹치면 5번 시도한 뒤 마지막 예외를 던진다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findById(1L)).thenAnswer { reservation() }
                whenever(reservationRepository.updateState(any(), eq(ReservationStatus.CONFIRMED))).thenReturn(false)

                assertThrows<ReservationChangedConcurrentlyException> { service.reallocate(command(5L to 2)) }

                verifyBlocking(reservationRepository, times(1 + 5)) { findById(1L) }
                verifyBlocking(holdInventoryAllocationUseCase, times(1)) { hold(any(), any()) }
            }

        @Test
        fun `밀린 사이 예약이 해제되었으면 다시 읽은 뒤 409로 끝난다`() =
            runBlocking<Unit> {
                whenever(
                    reservationRepository.findById(1L),
                ).thenReturn(reservation(), reservation(), reservation(ReservationStatus.RELEASED))
                whenever(reservationRepository.updateState(any(), eq(ReservationStatus.CONFIRMED))).thenReturn(false)

                assertThrows<InvalidReservationStateException> { service.reallocate(command(5L to 2)) }
            }
    }
}
