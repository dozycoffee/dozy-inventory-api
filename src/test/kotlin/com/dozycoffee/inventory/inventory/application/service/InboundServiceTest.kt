package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateLotException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.LotMismatchException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class InboundServiceTest {
    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var lotRepository: LotRepository

    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var inventoryEventPublisher: InventoryEventPublisher

    private lateinit var service: InboundService

    private val today: LocalDate = LocalDate.of(2026, 10, 7)
    private val mfg: LocalDate = LocalDate.of(2026, 9, 1)
    private val exp: LocalDate = LocalDate.of(2027, 3, 1)
    private val keyValue: String = "wms-inbound-item-77"
    private val key: IdempotencyKey = IdempotencyKey.of(keyValue)

    private val command: ConfirmInboundCommand =
        ConfirmInboundCommand(10L, 100L, 5, QualityStatus.NORMAL, "LOT-A", mfg, exp, 77L, keyValue, "svc-wms")

    private val product: ProductResult = ProductResult(100L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", 180, ProductStatus.ACTIVE)

    private fun lot(
        manufactureDate: LocalDate? = mfg,
        expirationDate: LocalDate? = exp,
    ): Lot = Lot.reconstitute(1000L, 100L, "LOT-A", manufactureDate, expirationDate, LotStatus.NORMAL)

    private fun inventory(quantity: Int = 15): Inventory =
        Inventory.reconstitute(500L, 10L, 100L, 1000L, QualityStatus.NORMAL, quantity, 0, false, null, null)

    private fun savedHistory(
        change: Int = 5,
        after: Int = 15,
        referenceId: Long = 77L,
    ): InventoryHistory =
        InventoryHistory.reconstitute(
            9000L,
            500L,
            HistoryType.INBOUND,
            change,
            after,
            ReferenceType.INBOUND_ITEM,
            referenceId,
            key,
            RequesterService.of("svc-wms"),
            LocalDateTime.of(2026, 10, 7, 9, 0),
        )

    @BeforeEach
    fun setUp() {
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC)
        service =
            InboundService(
                getProductUseCase,
                lotRepository,
                inventoryRepository,
                inventoryHistoryRepository,
                inventoryEventPublisher,
                DirectTransactionalOperator(),
                clock,
                30,
            )
    }

    private suspend fun stubFirstReceipt(existingLot: Lot? = null) {
        whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
        whenever(getProductUseCase.getById(100L)).thenReturn(product)
        whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(existingLot)
        if (existingLot == null) whenever(lotRepository.save(any())).thenReturn(lot())
        whenever(inventoryRepository.increase(InventoryKey(10L, 1000L, QualityStatus.NORMAL), 100L, 5)).thenReturn(inventory())
        whenever(inventoryHistoryRepository.save(any())).thenReturn(savedHistory())
    }

    @Nested
    inner class `처음 입고` {
        @Test
        fun `Lot을 등록하고 수량을 늘리고 이력과 이벤트를 남기고 처리 후 수량을 반환한다`() =
            runBlocking<Unit> {
                stubFirstReceipt()

                val result: InboundResult = service.confirm(command)

                assertEquals(InboundResult(500L, 10L, 100L, 1000L, QualityStatus.NORMAL, 5, 15, 9000L), result)
                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(HistoryType.INBOUND, history.firstValue.historyType)
                assertEquals(5, history.firstValue.quantityChange)
                assertEquals(15, history.firstValue.quantityAfter)
                assertEquals(ReferenceType.INBOUND_ITEM, history.firstValue.referenceType)
                assertEquals(77L, history.firstValue.referenceId)
                assertEquals(key, history.firstValue.idempotencyKey)
                assertEquals("svc-wms", history.firstValue.requesterService.value)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(inventoryEventPublisher) { publish(event.capture()) }
                assertEquals(
                    InventoryEvent(
                        InventoryEventType.INCREASED,
                        10L,
                        100L,
                        1000L,
                        QualityStatus.NORMAL,
                        5,
                        15,
                        ReferenceType.INBOUND_ITEM,
                        77L,
                        keyValue,
                    ),
                    event.firstValue,
                )
            }

        @Test
        fun `새 Lot의 상태는 기준일과 임박 일수로 정한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(null)
                whenever(lotRepository.save(any())).thenReturn(lot())
                whenever(inventoryRepository.increase(any(), eq(100L), eq(5))).thenReturn(inventory())
                whenever(inventoryHistoryRepository.save(any())).thenReturn(savedHistory())

                service.confirm(command.copy(expirationDate = today.plusDays(10)))

                val registered = argumentCaptor<Lot>()
                verifyBlocking(lotRepository) { save(registered.capture()) }
                assertEquals(LotStatus.EXPIRING_SOON, registered.firstValue.lotStatus)
                assertEquals("LOT-A", registered.firstValue.lotNumber)
            }

        @Test
        fun `불량 판정분은 DEFECTIVE 행에 반영한다`() =
            runBlocking<Unit> {
                val defective: Inventory = Inventory.reconstitute(501L, 10L, 100L, 1000L, QualityStatus.DEFECTIVE, 2, 0, false, null, null)
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
                whenever(inventoryRepository.increase(InventoryKey(10L, 1000L, QualityStatus.DEFECTIVE), 100L, 2)).thenReturn(defective)
                whenever(inventoryHistoryRepository.save(any())).thenReturn(savedHistory(change = 2, after = 2))

                val result: InboundResult = service.confirm(command.copy(quantity = 2, qualityStatus = QualityStatus.DEFECTIVE))

                assertEquals(QualityStatus.DEFECTIVE, result.qualityStatus)
                assertEquals(2, result.quantityAfter)
            }
    }

    @Nested
    inner class `Lot 재사용` {
        @Test
        fun `이미 있는 Lot은 날짜가 같으면 등록하지 않고 재사용한다`() =
            runBlocking<Unit> {
                stubFirstReceipt(existingLot = lot())

                service.confirm(command)

                verifyBlocking(lotRepository, never()) { save(any()) }
            }

        @Test
        fun `날짜가 다르면 Lot 불일치로 거부하고 수량은 바꾸지 않는다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot(expirationDate = exp.plusDays(1)))

                assertThrows<LotMismatchException> { service.confirm(command) }

                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
                verifyBlocking(inventoryEventPublisher, never()) { publish(any()) }
            }

        @Test
        fun `한쪽 날짜가 없는 것과 있는 것도 다르다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot(manufactureDate = null))

                assertThrows<LotMismatchException> { service.confirm(command) }
            }

        @Test
        fun `같은 Lot이 동시에 처음 들어와 등록이 중복되면 한 번 다시 시도한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(null, lot())
                whenever(lotRepository.save(any())).thenThrow(DuplicateLotException())
                whenever(inventoryRepository.increase(any(), eq(100L), eq(5))).thenReturn(inventory())
                whenever(inventoryHistoryRepository.save(any())).thenReturn(savedHistory())

                val result: InboundResult = service.confirm(command)

                assertEquals(15, result.quantityAfter)
                verifyBlocking(lotRepository, times(1)) { save(any()) }
            }
    }

    @Nested
    inner class `멱등 재요청` {
        private suspend fun stubPrevious(history: InventoryHistory = savedHistory()) {
            whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(listOf(history))
            whenever(inventoryRepository.findById(500L)).thenReturn(inventory(quantity = 40))
            whenever(lotRepository.findById(1000L)).thenReturn(lot())
        }

        @Test
        fun `같은 내용이면 반영하지 않고 처음 응답의 수량으로 이전 결과를 반환한다`() =
            runBlocking<Unit> {
                stubPrevious()

                val result: InboundResult = service.confirm(command)

                assertEquals(InboundResult(500L, 10L, 100L, 1000L, QualityStatus.NORMAL, 5, 15, 9000L), result)
                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
                verifyBlocking(inventoryEventPublisher, never()) { publish(any()) }
                verifyBlocking(getProductUseCase, never()) { getById(any()) }
            }

        @Test
        fun `같은 키에 다른 내용이 오면 거부한다`() =
            runBlocking<Unit> {
                listOf(
                    command.copy(quantity = 6),
                    command.copy(referenceId = 78L),
                    command.copy(warehouseId = 11L),
                    command.copy(productId = 101L),
                    command.copy(qualityStatus = QualityStatus.DEFECTIVE),
                    command.copy(lotNumber = "LOT-B"),
                ).forEach { different: ConfirmInboundCommand ->
                    stubPrevious()
                    assertThrows<IdempotencyKeyConflictException> { service.confirm(different) }
                }
            }

        @Test
        fun `같은 키의 이력이 여러 건이면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(listOf(savedHistory(), savedHistory()))

                assertThrows<IdempotencyKeyConflictException> { service.confirm(command) }
            }

        @Test
        fun `동시 요청에서 저장이 중복 키로 실패하면 먼저 저장된 이력으로 이전 결과를 반환한다`() =
            runBlocking<Unit> {
                val history: InventoryHistory = savedHistory()
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList(), listOf(history))
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
                whenever(inventoryRepository.increase(any(), eq(100L), eq(5))).thenReturn(inventory())
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())
                whenever(inventoryRepository.findById(500L)).thenReturn(inventory())
                whenever(lotRepository.findById(1000L)).thenReturn(lot())

                val result: InboundResult = service.confirm(command)

                assertEquals(9000L, result.inventoryHistoryId)
                verifyBlocking(inventoryEventPublisher, never()) { publish(any()) }
            }

        @Test
        fun `중복 키로 실패했는데 저장된 이력이 없으면 예외를 그대로 던진다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenReturn(product)
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
                whenever(inventoryRepository.increase(any(), eq(100L), eq(5))).thenReturn(inventory())
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())

                assertThrows<DuplicateIdempotencyKeyException> { service.confirm(command) }
            }
    }

    @Nested
    inner class `반려` {
        private suspend fun assertRejected(
            invalid: ConfirmInboundCommand,
            code: ErrorCode,
        ) {
            val e: InvalidDomainValueException = assertThrows { service.confirm(invalid) }
            assertEquals(code, e.errorCode)
            verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
            verifyBlocking(inventoryHistoryRepository, never()) { findAllByIdempotencyKey(any()) }
        }

        @Test
        fun `수량이 1 미만이면 반려한다`() =
            runBlocking<Unit> {
                assertRejected(command.copy(quantity = 0), InventoryErrorCode.INVALID_QUANTITY)
                assertRejected(command.copy(quantity = -3), InventoryErrorCode.INVALID_QUANTITY)
            }

        @Test
        fun `폐기 예정 품질 상태는 반려한다`() =
            runBlocking<Unit> {
                assertRejected(
                    command.copy(qualityStatus = QualityStatus.DISPOSAL_SCHEDULED),
                    InventoryErrorCode.INVALID_INBOUND_QUALITY_STATUS,
                )
            }

        @Test
        fun `멱등 키가 올바르지 않으면 반려한다`() =
            runBlocking<Unit> {
                assertRejected(command.copy(idempotencyKey = " "), CommonErrorCode.INVALID_IDEMPOTENCY_KEY)
                assertRejected(command.copy(idempotencyKey = "a".repeat(101)), CommonErrorCode.INVALID_IDEMPOTENCY_KEY)
                assertRejected(command.copy(requesterService = " "), CommonErrorCode.INVALID_REQUESTER_SERVICE)
                assertRejected(command.copy(requesterService = "a".repeat(51)), CommonErrorCode.INVALID_REQUESTER_SERVICE)
            }

        @Test
        fun `상품이 없으면 반려하고 아무것도 저장하지 않는다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(key)).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenThrow(ProductNotFoundException())

                assertThrows<ProductNotFoundException> { service.confirm(command) }

                verifyBlocking(lotRepository, never()) { save(any()) }
                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
            }

        @Test
        fun `비활성 상품도 입고할 수 있다`() =
            runBlocking<Unit> {
                stubFirstReceipt(existingLot = lot())
                whenever(getProductUseCase.getById(100L)).thenReturn(product.copy(productStatus = ProductStatus.INACTIVE))

                assertEquals(15, service.confirm(command).quantityAfter)
            }
    }
}
