package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmDisposalCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.DisposalResult
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
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
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
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class DisposalServiceTest {
    @Mock
    private lateinit var lotRepository: LotRepository

    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var publisher: InventoryEventPublisher

    private lateinit var service: DisposalService

    private val key: String = "wms-disposal-item-5"

    @BeforeEach
    fun setUp() {
        service = DisposalService(lotRepository, inventoryRepository, inventoryHistoryRepository, publisher, DirectTransactionalOperator())
    }

    private fun command(
        quantity: Int = 4,
        quality: QualityStatus = QualityStatus.DEFECTIVE,
        lotNumber: String = "LOT-A",
    ): ConfirmDisposalCommand = ConfirmDisposalCommand(10L, 100L, lotNumber, quality, quantity, 5L, key, "svc-wms")

    private fun lot(): Lot = Lot.reconstitute(7L, 100L, "LOT-A", null, null, LotStatus.NORMAL)

    private fun row(
        quantity: Int,
        quality: QualityStatus = QualityStatus.DEFECTIVE,
    ): Inventory = Inventory.reconstitute(50L, 10L, 100L, 7L, quality, quantity, 0, false, null, null)

    private fun history(
        change: Int = -4,
        type: HistoryType = HistoryType.DISPOSAL,
        referenceId: Long = 5L,
    ): InventoryHistory =
        InventoryHistory.reconstitute(
            900L,
            50L,
            type,
            change,
            6,
            ReferenceType.DISPOSAL_ITEM,
            referenceId,
            IdempotencyKey.of(key),
            RequesterService.of("svc-wms"),
            LocalDateTime.of(2026, 10, 13, 12, 0),
        )

    private suspend fun stubFresh(quality: QualityStatus = QualityStatus.DEFECTIVE) {
        whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(emptyList())
        whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(lot())
        whenever(inventoryRepository.findByKey(InventoryKey(10L, 7L, quality))).thenReturn(row(10, quality))
    }

    @Nested
    inner class `폐기 반영` {
        @Test
        fun `불량 재고를 차감하고 DISPOSAL 이력과 감소 이벤트를 남기며 반영 후 수량을 반환한다`() =
            runBlocking<Unit> {
                stubFresh()
                whenever(inventoryRepository.decrease(50L, 4)).thenReturn(row(6))
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

                val result: DisposalResult = service.confirm(command())

                assertEquals(DisposalResult(50L, 10L, 100L, 7L, QualityStatus.DEFECTIVE, -4, 6, 900L), result)
                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(HistoryType.DISPOSAL, history.firstValue.historyType)
                assertEquals(-4, history.firstValue.quantityChange)
                assertEquals(6, history.firstValue.quantityAfter)
                assertEquals(ReferenceType.DISPOSAL_ITEM, history.firstValue.referenceType)
                assertEquals(5L, history.firstValue.referenceId)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(InventoryEventType.DECREASED, event.firstValue.eventType)
                assertEquals(-4, event.firstValue.quantityChange)
                assertEquals(ReferenceType.DISPOSAL_ITEM, event.firstValue.referenceType)
            }

        @Test
        fun `폐기 예정 재고도 폐기할 수 있다`() =
            runBlocking<Unit> {
                stubFresh(QualityStatus.DISPOSAL_SCHEDULED)
                whenever(inventoryRepository.decrease(50L, 4)).thenReturn(row(6, QualityStatus.DISPOSAL_SCHEDULED))
                whenever(inventoryHistoryRepository.save(any())).thenReturn(history())

                service.confirm(command(quality = QualityStatus.DISPOSAL_SCHEDULED))

                verifyBlocking(inventoryRepository) { decrease(50L, 4) }
            }
    }

    @Nested
    inner class `반려` {
        @Test
        fun `정상 품질이면 거부하고 아무것도 조회하거나 저장하지 않는다`() =
            runBlocking<Unit> {
                val e: InvalidDomainValueException =
                    assertThrows { runBlocking { service.confirm(command(quality = QualityStatus.NORMAL)) } }

                assertEquals(InventoryErrorCode.INVALID_DISPOSAL_QUALITY_STATUS.code, e.errorCode.code)
                verifyBlocking(inventoryHistoryRepository, never()) { findAllByIdempotencyKey(any()) }
                verifyBlocking(lotRepository, never()) { findByProductIdAndLotNumber(any(), any()) }
                verifyBlocking(inventoryRepository, never()) { decrease(any(), any()) }
            }

        @Test
        fun `수량이 1 미만이면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<InvalidDomainValueException> { service.confirm(command(quantity = 0)) }
            }

        @Test
        fun `등록되지 않은 Lot이나 재고 행이 없으면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(emptyList())
                whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(null).thenReturn(lot())
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 7L, QualityStatus.DEFECTIVE))).thenReturn(null)

                assertThrows<LotNotFoundException> { service.confirm(command()) }
                assertThrows<InventoryNotFoundException> { service.confirm(command()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
            }

        @Test
        fun `가용 수량을 넘으면 거부하고 이력과 이벤트를 남기지 않는다`() =
            runBlocking<Unit> {
                stubFresh()
                whenever(inventoryRepository.decrease(50L, 4)).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.confirm(command()) }

                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }
    }

    @Nested
    inner class `멱등` {
        private suspend fun stubPrevious() {
            whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(listOf(history()))
            whenever(inventoryRepository.findById(50L)).thenReturn(row(6))
            whenever(lotRepository.findById(7L)).thenReturn(lot())
        }

        @Test
        fun `같은 멱등 키의 같은 내용은 차감하지 않고 저장된 이력으로 이전 결과를 반환한다`() =
            runBlocking<Unit> {
                stubPrevious()

                val result: DisposalResult = service.confirm(command())

                assertEquals(DisposalResult(50L, 10L, 100L, 7L, QualityStatus.DEFECTIVE, -4, 6, 900L), result)
                verifyBlocking(inventoryRepository, never()) { decrease(any(), any()) }
            }

        @Test
        fun `같은 멱등 키에 다른 내용이 오면 거부한다`() =
            runBlocking<Unit> {
                stubPrevious()

                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(quantity = 5)) }
                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(lotNumber = "LOT-B")) }
                assertThrows<IdempotencyKeyConflictException> { service.confirm(command(quality = QualityStatus.DISPOSAL_SCHEDULED)) }
            }

        @Test
        fun `다른 종류의 이력이 같은 키를 쓰고 있으면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key)))
                    .thenReturn(listOf(history(change = 4, type = HistoryType.INBOUND)))
                whenever(inventoryRepository.findById(50L)).thenReturn(row(6))
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
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 7L, QualityStatus.DEFECTIVE))).thenReturn(row(10))
                whenever(inventoryRepository.decrease(50L, 4)).thenReturn(row(6))
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())
                whenever(inventoryRepository.findById(50L)).thenReturn(row(6))
                whenever(lotRepository.findById(7L)).thenReturn(lot())

                assertEquals(900L, service.confirm(command()).inventoryHistoryId)
            }
    }
}
