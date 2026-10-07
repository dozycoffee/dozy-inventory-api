package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import com.dozycoffee.inventory.reservation.application.port.out.ReservationEventRepository
import com.dozycoffee.inventory.reservation.application.port.out.ReservationPolicy
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateOrderReservationException
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateReservationKeyException
import com.dozycoffee.inventory.reservation.domain.exception.ProductNotReservableException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
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
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class ReservationServiceTest {
    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var allocateInventoryUseCase: AllocateInventoryUseCase

    @Mock
    private lateinit var reservationRepository: ReservationRepository

    @Mock
    private lateinit var reservationEventRepository: ReservationEventRepository

    @Mock
    private lateinit var publisher: ReservationChangedEventPublisher

    private lateinit var service: ReservationService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 12, 0)
    private val keyValue: String = "svc-oms-order-1"
    private val key: IdempotencyKey = IdempotencyKey.of(keyValue)
    private val activeProduct: ProductResult = ProductResult(100L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", 180, ProductStatus.ACTIVE)

    private fun command(
        items: List<CreateReservationCommand.Item> = listOf(CreateReservationCommand.Item(100L, 10)),
        expiresAt: LocalDateTime = now.plusMinutes(30),
        channel: String = "OMS",
        key: String = keyValue,
    ): CreateReservationCommand = CreateReservationCommand(10L, channel, "ORDER-1", items, expiresAt, key, "svc-oms")

    private val allocated: AllocationResult =
        AllocationResult(
            listOf(
                AllocationResult.ItemAllocation(
                    100L,
                    listOf(
                        AllocationResult.LotAllocation(5L, 55L, LocalDate.of(2027, 1, 1), 4),
                        AllocationResult.LotAllocation(7L, 77L, LocalDate.of(2027, 2, 1), 6),
                    ),
                ),
            ),
        )

    private fun stored(
        id: Long = 1L,
        status: ReservationStatus = ReservationStatus.RESERVED,
        quantity: Int = 10,
        orderId: String = "ORDER-1",
    ): Reservation =
        Reservation.reconstitute(
            id,
            10L,
            ReservationChannel.of("OMS"),
            ExternalOrderId.of(orderId),
            status,
            ReservationExpiry.reconstitute(now.plusMinutes(30), now.plusHours(1)),
            null,
            key,
            RequesterService.of("svc-oms"),
            listOf(
                ReservationItem.reconstitute(
                    11L,
                    100L,
                    quantity,
                    listOf(ReservationAllocation.reconstitute(21L, 5L, 4, 0), ReservationAllocation.reconstitute(22L, 7L, quantity - 4, 0)),
                ),
            ),
        )

    @BeforeEach
    fun setUp() {
        val clock: Clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val policy: ReservationPolicy = ReservationPolicy { channel: String -> Duration.ofHours(if (channel == "STORE") 48 else 1) }
        service =
            ReservationService(
                getProductUseCase,
                allocateInventoryUseCase,
                reservationRepository,
                reservationEventRepository,
                publisher,
                DirectTransactionalOperator(),
                clock,
                policy,
            )
    }

    private suspend fun stubSuccess() {
        whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
        whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(false)
        whenever(allocateInventoryUseCase.allocate(any())).thenReturn(allocated)
        whenever(reservationRepository.save(any())).thenAnswer { stored() }
        whenever(reservationEventRepository.save(any())).thenAnswer { it.getArgument<ReservationEvent>(0) }
    }

    private fun assertRejected(
        code: ErrorCode,
        block: suspend () -> Unit,
    ) {
        val e: InvalidDomainValueException = assertThrows { runBlocking { block() } }
        assertEquals(code, e.errorCode)
    }

    private suspend fun verifyNothingWritten() {
        verifyBlocking(allocateInventoryUseCase, never()) { allocate(any()) }
        verifyBlocking(reservationRepository, never()) { save(any()) }
    }

    @Nested
    inner class `생성` {
        @Test
        fun `재고를 할당하고 예약을 저장해 할당 결과를 반환한다`() =
            runBlocking<Unit> {
                stubSuccess()

                val result: ReservationResult = service.create(command())

                assertEquals(1L, result.reservationId)
                assertEquals(ReservationStatus.RESERVED, result.status)
                assertEquals("OMS", result.channel)
                assertEquals(
                    listOf(5L to 4, 7L to 6),
                    result.items
                        .single()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                )
                val allocationCommand = argumentCaptor<AllocateInventoryCommand>()
                verifyBlocking(allocateInventoryUseCase) { allocate(allocationCommand.capture()) }
                assertEquals(AllocateInventoryCommand(10L, listOf(AllocateInventoryCommand.Item(100L, 10))), allocationCommand.firstValue)
            }

        @Test
        fun `저장하는 예약은 요청 수량과 할당 결과와 만료 시각을 가진다`() =
            runBlocking<Unit> {
                stubSuccess()

                service.create(command())

                val saved = argumentCaptor<Reservation>()
                verifyBlocking(reservationRepository) { save(saved.capture()) }
                val reservation: Reservation = saved.firstValue
                assertEquals(ReservationStatus.RESERVED, reservation.status)
                assertEquals(now.plusMinutes(30), reservation.expiry.expiresAt)
                assertEquals(now.plusHours(1), reservation.expiry.maxExpiresAt)
                assertEquals("svc-oms", reservation.requesterService.value)
                assertEquals(10, reservation.items.single().requestedQuantity)
                assertEquals(
                    listOf(5L to 4, 7L to 6),
                    reservation.items
                        .single()
                        .allocations
                        .map { it.inventoryId to it.quantity },
                )
            }

        @Test
        fun `CREATED 이력을 남기고 이벤트를 발행한다`() =
            runBlocking<Unit> {
                stubSuccess()

                service.create(command())

                val history = argumentCaptor<ReservationEvent>()
                verifyBlocking(reservationEventRepository) { save(history.capture()) }
                assertEquals(ReservationEventType.CREATED, history.firstValue.eventType)
                assertEquals(1L, history.firstValue.reservationId)
                val published = argumentCaptor<ReservationChangedEvent>()
                verifyBlocking(publisher) { publish(published.capture()) }
                assertEquals(ReservationChangeType.CREATED, published.firstValue.changeType)
                assertEquals(keyValue, published.firstValue.idempotencyKey)
                assertEquals(listOf(ReservationChangedEvent.Item(100L, 10)), published.firstValue.items)
            }
    }

    @Nested
    inner class `요청 검증` {
        @Test
        fun `형식 오류는 어떤 것도 쓰기 전에 거부한다`() =
            runBlocking<Unit> {
                assertRejected(CommonErrorCode.INVALID_IDEMPOTENCY_KEY) { service.create(command(key = " ")) }
                assertRejected(ReservationErrorCode.INVALID_RESERVATION_CHANNEL) { service.create(command(channel = "")) }
                assertRejected(CommonErrorCode.INVALID_REQUESTER_SERVICE) { service.create(command().copy(requesterService = " ")) }
                assertRejected(ReservationErrorCode.INVALID_EXTERNAL_ORDER_ID) { service.create(command().copy(externalOrderId = "")) }
                assertRejected(InventoryErrorCode.INVALID_ALLOCATION_REQUEST) {
                    service.create(command(items = emptyList()))
                }

                verifyNothingWritten()
                verifyBlocking(getProductUseCase, never()) { getById(any()) }
            }

        @Test
        fun `만료 시각이 현재 이전이거나 채널 상한을 넘으면 거부한다`() =
            runBlocking<Unit> {
                assertRejected(ReservationErrorCode.INVALID_RESERVATION_EXPIRY) { service.create(command(expiresAt = now)) }
                assertRejected(
                    ReservationErrorCode.INVALID_RESERVATION_EXPIRY,
                ) { service.create(command(expiresAt = now.plusHours(1).plusSeconds(1))) }

                verifyNothingWritten()
            }

        @Test
        fun `채널별 상한이 있으면 그 상한까지 허용한다`() =
            runBlocking<Unit> {
                stubSuccess()

                service.create(command(channel = "STORE", expiresAt = now.plusHours(48)))
                assertRejected(ReservationErrorCode.INVALID_RESERVATION_EXPIRY) {
                    service.create(command(channel = "STORE", expiresAt = now.plusHours(48).plusSeconds(1), key = "other"))
                }
            }
    }

    @Nested
    inner class `상품과 주문 검증` {
        @Test
        fun `비활성 상품은 할당하지 않고 거부한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct.copy(productStatus = ProductStatus.INACTIVE))

                assertThrows<ProductNotReservableException> { service.create(command()) }

                verifyNothingWritten()
            }

        @Test
        fun `없는 상품은 상품 없음 예외가 그대로 나간다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenThrow(ProductNotFoundException())

                assertThrows<ProductNotFoundException> { service.create(command()) }

                verifyNothingWritten()
            }

        @Test
        fun `같은 채널의 같은 주문에 살아 있는 예약이 있으면 거부한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(true)

                assertThrows<DuplicateOrderReservationException> { service.create(command()) }

                verifyNothingWritten()
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 키의 재요청은 새로 잡지 않고 저장된 예약을 반환한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(stored())

                val result: ReservationResult = service.create(command(expiresAt = now.plusMinutes(10)))

                assertEquals(1L, result.reservationId)
                assertEquals(now.plusMinutes(30), result.expiresAt)
                verifyNothingWritten()
                verifyBlocking(publisher, never()) { publish(any()) }
            }

        @Test
        fun `재요청 시점에 예약 상태가 바뀌었으면 바뀐 상태를 반환한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(stored(status = ReservationStatus.CONFIRMED))

                assertEquals(ReservationStatus.CONFIRMED, service.create(command()).status)
            }

        @Test
        fun `같은 키에 다른 내용이면 거부한다`() =
            runBlocking<Unit> {
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(stored())

                assertThrows<IdempotencyKeyConflictException> {
                    service.create(command(items = listOf(CreateReservationCommand.Item(100L, 11))))
                }
                assertThrows<IdempotencyKeyConflictException> { service.create(command().copy(externalOrderId = "ORDER-2")) }
                assertThrows<IdempotencyKeyConflictException> { service.create(command().copy(warehouseId = 11L)) }
                assertThrows<IdempotencyKeyConflictException> { service.create(command(channel = "STORE")) }
                assertThrows<IdempotencyKeyConflictException> {
                    service.create(command(items = listOf(CreateReservationCommand.Item(100L, 10), CreateReservationCommand.Item(101L, 1))))
                }
                verifyNothingWritten()
            }

        @Test
        fun `동시에 같은 키가 들어와 중복 키로 롤백되면 저장된 예약을 반환한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(false)
                whenever(allocateInventoryUseCase.allocate(any())).thenReturn(allocated)
                whenever(reservationRepository.save(any())).thenThrow(DuplicateReservationKeyException())
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(null).thenReturn(stored())

                val result: ReservationResult = service.create(command())

                assertEquals(1L, result.reservationId)
            }
    }

    @Nested
    inner class `같은 키의 동시 요청` {
        @Test
        fun `먼저 온 같은 키의 예약이 중복 주문 검사에 걸리면 저장된 예약을 반환한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(true)
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(null).thenReturn(stored())

                assertEquals(1L, service.create(command()).reservationId)
                verifyNothingWritten()
            }

        @Test
        fun `같은 키의 예약이 없으면 중복 주문 오류가 그대로 나간다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(true)
                whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(null)

                assertThrows<DuplicateOrderReservationException> { service.create(command()) }
            }
    }

    @Nested
    inner class `경합과 부족` {
        @Test
        fun `할당 경합이면 새 트랜잭션으로 다시 시도해 성공한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(false)
                whenever(allocateInventoryUseCase.allocate(any()))
                    .thenThrow(AllocationConflictException())
                    .thenThrow(AllocationConflictException())
                    .thenReturn(allocated)
                whenever(reservationRepository.save(any())).thenAnswer { stored() }
                whenever(reservationEventRepository.save(any())).thenAnswer { it.getArgument<ReservationEvent>(0) }

                assertEquals(1L, service.create(command()).reservationId)

                verifyBlocking(allocateInventoryUseCase, times(3)) { allocate(any()) }
            }

        @Test
        fun `재시도를 모두 소진하면 경합 오류로 실패하고 저장하지 않는다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(false)
                whenever(allocateInventoryUseCase.allocate(any())).thenThrow(AllocationConflictException())

                assertThrows<AllocationConflictException> { service.create(command()) }

                verifyBlocking(allocateInventoryUseCase, times(5)) { allocate(any()) }
                verifyBlocking(reservationRepository, never()) { save(any()) }
            }

        @Test
        fun `가용 수량이 부족하면 재시도 없이 그대로 실패한다`() =
            runBlocking<Unit> {
                whenever(getProductUseCase.getById(100L)).thenReturn(activeProduct)
                whenever(reservationRepository.existsActive(any(), any(), any())).thenReturn(false)
                whenever(allocateInventoryUseCase.allocate(any())).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.create(command()) }

                verifyBlocking(allocateInventoryUseCase, times(1)) { allocate(any()) }
                verifyBlocking(reservationRepository, never()) { save(any()) }
            }
    }
}
