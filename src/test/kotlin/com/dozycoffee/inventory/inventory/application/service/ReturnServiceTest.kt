package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ReturnResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class ReturnServiceTest {
    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var lotRepository: LotRepository

    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var publisher: InventoryEventPublisher

    private lateinit var service: ReturnService

    private val key: String = "wms-return-item-5"

    @BeforeEach
    fun setUp() {
        service =
            ReturnService(
                getProductUseCase,
                lotRepository,
                inventoryRepository,
                inventoryHistoryRepository,
                publisher,
                DirectTransactionalOperator(),
            )
    }

    private fun command(
        quantity: Int = 4,
        quality: QualityStatus = QualityStatus.NORMAL,
        lotNumber: String = "LOT-A",
    ): ConfirmReturnCommand = ConfirmReturnCommand(10L, 100L, lotNumber, quality, quantity, 5L, key, "svc-wms")

    private fun lot(): Lot = Lot.reconstitute(7L, 100L, "LOT-A", null, null, LotStatus.NORMAL)

    private fun row(
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): Inventory = Inventory.reconstitute(50L, 10L, 100L, 7L, quality, quantity, 0, false, null, null)

    private fun history(
        change: Int = 4,
        type: HistoryType = HistoryType.RETURN,
        referenceId: Long = 5L,
    ): InventoryHistory =
        InventoryHistory.reconstitute(
            900L,
            50L,
            type,
            change,
            14,
            ReferenceType.RETURN_ITEM,
            referenceId,
            IdempotencyKey.of(key),
            RequesterService.of("svc-wms"),
            LocalDateTime.of(2026, 10, 13, 12, 0),
        )

    private suspend fun stubFresh() {
        whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(emptyList())
        whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
    }

    @Nested
    inner class `반품 복귀` {
        @Test
        fun `재고를 늘리고 RETURN 이력과 증가 이벤트를 남기며 반영 후 수량을 반환한다`() =
            runBlocking<Unit> {
                stubFresh()
                whenever(inventoryRepository.increase(InventoryKey(10L, 7L, QualityStatus.NORMAL), 100L, 4)).thenReturn(row(14))
                whenever(inventoryHistoryRepository.save(any())).thenAnswer {
                    val saved: InventoryHistory = it.getArgument(0)
                    InventoryHistory.reconstitute(
                        900L,
                        saved.inventoryId,
                        saved.historyType,
                        saved.quantityChange,
                        saved.quantityAfter,
                        saved.referenceType,
                        saved.referenceId,
                        saved.idempotencyKey,
                        saved.requesterService,
                        LocalDateTime.of(2026, 10, 13, 12, 0),
                    )
                }

                val result: ReturnResult = service.confirm(command())

                assertEquals(ReturnResult(50L, 10L, 100L, 7L, QualityStatus.NORMAL, 4, 14, 900L), result)
                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(HistoryType.RETURN, history.firstValue.historyType)
                assertEquals(4, history.firstValue.quantityChange)
                assertEquals(14, history.firstValue.quantityAfter)
                assertEquals(ReferenceType.RETURN_ITEM, history.firstValue.referenceType)
                assertEquals(5L, history.firstValue.referenceId)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(InventoryEventType.INCREASED, event.firstValue.eventType)
                assertEquals(4, event.firstValue.quantityChange)
                assertEquals(ReferenceType.RETURN_ITEM, event.firstValue.referenceType)
            }

        @Test
        fun `불량 판정은 불량 품질 행에 반영한다`() =
            runBlocking<Unit> {
                stubFresh()
                whenever(inventoryRepository.increase(InventoryKey(10L, 7L, QualityStatus.DEFECTIVE), 100L, 4))
                    .thenReturn(row(4, QualityStatus.DEFECTIVE))
                whenever(inventoryHistoryRepository.save(any())).thenReturn(history())

                service.confirm(command(quality = QualityStatus.DEFECTIVE))

                verifyBlocking(inventoryRepository) { increase(InventoryKey(10L, 7L, QualityStatus.DEFECTIVE), 100L, 4) }
            }
    }

    @Nested
    inner class `반려` {
        @Test
        fun `폐기 예정 품질 상태는 거부하고 아무것도 조회하거나 저장하지 않는다`() =
            runBlocking<Unit> {
                val e: InvalidDomainValueException =
                    assertThrows { runBlocking { service.confirm(command(quality = QualityStatus.DISPOSAL_SCHEDULED)) } }

                assertEquals(InventoryErrorCode.INVALID_RETURN_QUALITY_STATUS.code, e.errorCode.code)
                verifyBlocking(inventoryHistoryRepository, never()) { findAllByIdempotencyKey(any()) }
                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
            }

        @Test
        fun `수량이 1 미만이면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<InvalidDomainValueException> { service.confirm(command(quantity = 0)) }
            }

        @Test
        fun `없는 상품이나 등록되지 않은 Lot이면 거부하고 아무것도 저장하지 않는다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(emptyList())
                whenever(getProductUseCase.getById(100L)).thenThrow(ProductNotFoundException()).thenReturn(mock())
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(null)

                assertThrows<ProductNotFoundException> { service.confirm(command()) }
                assertThrows<LotNotFoundException> { service.confirm(command()) }

                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
            }
    }

    @Nested
    inner class `멱등` {
        private suspend fun stubPrevious() {
            whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(listOf(history()))
            whenever(inventoryRepository.findById(50L)).thenReturn(row(14))
            whenever(lotRepository.findById(7L)).thenReturn(lot())
        }

        @Test
        fun `같은 멱등 키의 같은 내용은 반영하지 않고 저장된 이력으로 이전 결과를 반환한다`() =
            runBlocking<Unit> {
                stubPrevious()

                val result: ReturnResult = service.confirm(command())

                assertEquals(ReturnResult(50L, 10L, 100L, 7L, QualityStatus.NORMAL, 4, 14, 900L), result)
                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
            }

        @Test
        fun `같은 멱등 키에 다른 내용이 오면 거부한다`() =
            runBlocking<Unit> {
                stubPrevious()

                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(quantity = 5)) }
                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(lotNumber = "LOT-B")) }
                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(quality = QualityStatus.DEFECTIVE)) }
            }

        @Test
        fun `다른 종류의 이력이 같은 키를 쓰고 있으면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key)))
                    .thenReturn(listOf(history(type = HistoryType.INBOUND)))
                whenever(inventoryRepository.findById(50L)).thenReturn(row(14))
                whenever(lotRepository.findById(7L)).thenReturn(lot())

                assertThrows<IdempotencyKeyConflictException> { service.confirm(command()) }
            }

        @Test
        fun `동시에 같은 키가 들어와 이력 저장이 중복으로 실패하면 먼저 처리된 결과를 반환한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key)))
                    .thenReturn(emptyList())
                    .thenReturn(listOf(history()))
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
                whenever(inventoryRepository.increase(any(), eq(100L), eq(4))).thenReturn(row(14))
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())
                whenever(inventoryRepository.findById(50L)).thenReturn(row(14))
                whenever(lotRepository.findById(7L)).thenReturn(lot())

                assertEquals(900L, service.confirm(command()).inventoryHistoryId)
            }
    }
}
