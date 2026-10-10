package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
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
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class InventoryAdjustmentServiceTest {
    @Mock
    private lateinit var lotRepository: LotRepository

    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var publisher: InventoryEventPublisher

    private lateinit var service: InventoryAdjustmentService

    private val key: String = "wms-audit-1"

    @BeforeEach
    fun setUp() {
        service = InventoryAdjustmentService(lotRepository, inventoryRepository, inventoryHistoryRepository, publisher)
    }

    private fun item(
        lotId: Long,
        change: Int,
        referenceId: Long = lotId,
        quality: QualityStatus = QualityStatus.NORMAL,
        productId: Long = 100L,
    ): AdjustInventoryCommand.Item = AdjustInventoryCommand.Item(productId, lotId, quality, change, referenceId)

    private fun command(vararg items: AdjustInventoryCommand.Item): AdjustInventoryCommand =
        AdjustInventoryCommand(10L, items.toList(), key, "svc-wms")

    private fun inventory(
        id: Long,
        lotId: Long,
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
        hold: Boolean = false,
    ): Inventory = Inventory.reconstitute(id, 10L, 100L, lotId, quality, quantity, 0, hold, if (hold) "보류" else null, null)

    private suspend fun stubLot(
        lotId: Long,
        productId: Long = 100L,
    ) {
        whenever(lotRepository.findById(lotId)).thenReturn(Lot.reconstitute(lotId, productId, "LOT-$lotId", null, null, LotStatus.NORMAL))
    }

    private suspend fun stubExisting(
        id: Long,
        lotId: Long,
        quantity: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
        hold: Boolean = false,
    ) {
        stubLot(lotId)
        whenever(inventoryRepository.findByKey(InventoryKey(10L, lotId, quality))).thenReturn(inventory(id, lotId, quantity, quality, hold))
    }

    private suspend fun stubSave() {
        whenever(inventoryHistoryRepository.save(any())).thenAnswer { it.getArgument<InventoryHistory>(0) }
    }

    @Nested
    inner class `증가` {
        @Test
        fun `행이 없으면 만들고 ADJUSTMENT 이력과 증가 이벤트를 남긴다`() =
            runBlocking<Unit> {
                stubLot(1L)
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 1L, QualityStatus.NORMAL))).thenReturn(null)
                whenever(
                    inventoryRepository.increase(InventoryKey(10L, 1L, QualityStatus.NORMAL), 100L, 7),
                ).thenReturn(inventory(5L, 1L, 7))
                stubSave()

                val result: AdjustInventoryResult = service.adjust(command(item(1L, 7, referenceId = 31L)))

                assertEquals(listOf(AdjustInventoryResult.Item(31L, 5L, 7)), result.items)
                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(HistoryType.ADJUSTMENT, history.firstValue.historyType)
                assertEquals(7, history.firstValue.quantityChange)
                assertEquals(7, history.firstValue.quantityAfter)
                assertEquals(ReferenceType.STOCK_ADJUSTMENT_ITEM, history.firstValue.referenceType)
                assertEquals(31L, history.firstValue.referenceId)
                assertEquals(IdempotencyKey.of(key), history.firstValue.idempotencyKey)
                assertEquals(RequesterService.of("svc-wms"), history.firstValue.requesterService)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(InventoryEventType.INCREASED, event.firstValue.eventType)
                assertEquals(7, event.firstValue.quantityChange)
                assertEquals(ReferenceType.STOCK_ADJUSTMENT_ITEM, event.firstValue.referenceType)
            }

        @Test
        fun `행이 있으면 더하고 보류 중이면 보류를 푼다`() =
            runBlocking<Unit> {
                stubExisting(5L, 1L, 10, hold = true)
                whenever(inventoryRepository.increase(any(), eq(100L), eq(4))).thenReturn(inventory(5L, 1L, 14, hold = true))
                stubSave()

                service.adjust(command(item(1L, 4)))

                verifyBlocking(inventoryRepository) { releaseAllocationHold(5L) }
            }

        @Test
        fun `보류가 아니면 보류 해제를 호출하지 않는다`() =
            runBlocking<Unit> {
                stubExisting(5L, 1L, 10)
                whenever(inventoryRepository.increase(any(), eq(100L), eq(4))).thenReturn(inventory(5L, 1L, 14))
                stubSave()

                service.adjust(command(item(1L, 4)))

                verifyBlocking(inventoryRepository, never()) { releaseAllocationHold(any()) }
            }
    }

    @Nested
    inner class `감소` {
        @Test
        fun `가용 수량 안에서 줄이고 이력의 변동량은 음수이며 감소 이벤트를 남긴다`() =
            runBlocking<Unit> {
                stubExisting(5L, 1L, 10)
                whenever(inventoryRepository.decrease(5L, 3)).thenReturn(inventory(5L, 1L, 7))
                stubSave()

                val result: AdjustInventoryResult = service.adjust(command(item(1L, -3, referenceId = 31L)))

                assertEquals(listOf(AdjustInventoryResult.Item(31L, 5L, 7)), result.items)
                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(-3, history.firstValue.quantityChange)
                assertEquals(7, history.firstValue.quantityAfter)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(InventoryEventType.DECREASED, event.firstValue.eventType)
            }

        @Test
        fun `감소할 행이 없으면 재고 없음으로 거부한다`() =
            runBlocking<Unit> {
                stubLot(1L)
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 1L, QualityStatus.NORMAL))).thenReturn(null)

                assertThrows<InventoryNotFoundException> { service.adjust(command(item(1L, -3))) }

                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
            }

        @Test
        fun `가용 수량을 넘으면 거부하고 이력과 이벤트를 남기지 않는다`() =
            runBlocking<Unit> {
                stubExisting(5L, 1L, 10)
                whenever(inventoryRepository.decrease(5L, 99)).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.adjust(command(item(1L, -99))) }

                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }
    }

    @Nested
    inner class `여러 항목` {
        @Test
        fun `이미 있는 행은 ID 오름차순으로 갱신하고 결과는 요청 순서다`() =
            runBlocking<Unit> {
                stubExisting(9L, 1L, 10)
                stubExisting(3L, 2L, 10)
                stubExisting(7L, 3L, 10)
                whenever(inventoryRepository.decrease(any(), eq(1))).thenAnswer { inventory(it.getArgument<Long>(0), 0L, 9) }
                stubSave()

                val result: AdjustInventoryResult =
                    service.adjust(
                        command(item(1L, -1, referenceId = 11L), item(2L, -1, referenceId = 12L), item(3L, -1, referenceId = 13L)),
                    )

                assertEquals(listOf(11L, 12L, 13L), result.items.map { it.referenceId })
                assertEquals(listOf(9L, 3L, 7L), result.items.map { it.inventoryId })
                inOrder(inventoryRepository) {
                    verify(inventoryRepository).decrease(3L, 1)
                    verify(inventoryRepository).decrease(7L, 1)
                    verify(inventoryRepository).decrease(9L, 1)
                }
            }

        @Test
        fun `새로 만들 행은 이미 있는 행 뒤에 Lot과 품질 상태 순서로 만든다`() =
            runBlocking<Unit> {
                stubExisting(8L, 5L, 10)
                stubLot(2L)
                stubLot(1L)
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 2L, QualityStatus.NORMAL))).thenReturn(null)
                whenever(inventoryRepository.findByKey(InventoryKey(10L, 1L, QualityStatus.DEFECTIVE))).thenReturn(null)
                whenever(inventoryRepository.decrease(8L, 1)).thenReturn(inventory(8L, 5L, 9))
                whenever(inventoryRepository.increase(any(), eq(100L), eq(2))).thenAnswer {
                    val rowKey: InventoryKey = it.getArgument(0)
                    inventory(20L + rowKey.lotId, rowKey.lotId, 2, rowKey.qualityStatus)
                }
                stubSave()

                service.adjust(
                    command(
                        item(2L, 2, referenceId = 1L),
                        item(1L, 2, referenceId = 2L, quality = QualityStatus.DEFECTIVE),
                        item(5L, -1, referenceId = 3L),
                    ),
                )

                inOrder(inventoryRepository) {
                    verify(inventoryRepository).decrease(8L, 1)
                    verify(inventoryRepository).increase(InventoryKey(10L, 1L, QualityStatus.DEFECTIVE), 100L, 2)
                    verify(inventoryRepository).increase(InventoryKey(10L, 2L, QualityStatus.NORMAL), 100L, 2)
                }
            }
    }

    @Nested
    inner class `Lot 확인` {
        @Test
        fun `Lot이 없으면 거부한다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(1L)).thenReturn(null)

                assertThrows<LotNotFoundException> { service.adjust(command(item(1L, 5))) }

                verifyBlocking(inventoryRepository, never()) { increase(any(), any(), any()) }
            }

        @Test
        fun `Lot이 다른 상품의 것이면 없는 Lot으로 거부한다`() =
            runBlocking<Unit> {
                stubLot(1L, productId = 999L)

                assertThrows<LotNotFoundException> { service.adjust(command(item(1L, 5, productId = 100L))) }
            }
    }

    @Nested
    inner class `이전 결과 조회` {
        @Test
        fun `같은 멱등 키의 조정 이력에서 항목별 반영 후 수량을 만든다`() =
            runBlocking<Unit> {
                fun history(
                    type: HistoryType,
                    referenceType: ReferenceType,
                    referenceId: Long,
                    after: Int,
                ): InventoryHistory =
                    InventoryHistory.reconstitute(
                        referenceId,
                        referenceId,
                        type,
                        1,
                        after,
                        referenceType,
                        referenceId,
                        IdempotencyKey.of(key),
                        RequesterService.of("svc-wms"),
                        java.time.LocalDateTime.now(),
                    )
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(
                    listOf(
                        history(HistoryType.ADJUSTMENT, ReferenceType.STOCK_ADJUSTMENT_ITEM, 31L, 12),
                        history(HistoryType.ADJUSTMENT, ReferenceType.STOCK_ADJUSTMENT_ITEM, 32L, 7),
                        history(HistoryType.OUTBOUND, ReferenceType.RESERVATION, 99L, 1),
                    ),
                )

                assertEquals(mapOf(31L to 12, 32L to 7), service.getQuantitiesAfter(key))
            }
    }
}
