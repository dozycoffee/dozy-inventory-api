package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
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
class OutboundServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var publisher: InventoryEventPublisher

    private lateinit var service: OutboundService

    private val key: String = "wms-outbound-1"

    @BeforeEach
    fun setUp() {
        service = OutboundService(inventoryRepository, inventoryHistoryRepository, publisher)
    }

    private fun command(vararg items: ShipInventoryCommand.Item): ShipInventoryCommand =
        ShipInventoryCommand(items.toList(), key, 77L, "svc-wms")

    private fun inventory(
        id: Long,
        quantity: Int,
    ): Inventory = Inventory.reconstitute(id, 10L, 100L, id + 1000, QualityStatus.NORMAL, quantity, 0, false, null, null)

    private suspend fun stubShip(
        id: Long,
        quantityAfter: Int,
    ) {
        whenever(inventoryRepository.ship(eq(id), any())).thenReturn(inventory(id, quantityAfter))
        whenever(inventoryHistoryRepository.save(any())).thenAnswer { it.getArgument<InventoryHistory>(0) }
    }

    @Nested
    inner class `출고 반영` {
        @Test
        fun `출고한 수량만큼 총 수량과 예약 수량을 줄이고 처리 후 수량을 반환한다`() =
            runBlocking<Unit> {
                stubShip(5L, 6)

                val result: ShipResult = service.ship(command(ShipInventoryCommand.Item(5L, 4, 0)))

                assertEquals(listOf(ShipResult.Item(5L, 6)), result.items)
                verifyBlocking(inventoryRepository) { ship(5L, 4) }
                verifyBlocking(inventoryRepository, never()) { release(any(), any()) }
            }

        @Test
        fun `OUTBOUND 이력과 감소 이벤트를 남긴다`() =
            runBlocking<Unit> {
                stubShip(5L, 6)

                service.ship(command(ShipInventoryCommand.Item(5L, 4, 0)))

                val history = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository) { save(history.capture()) }
                assertEquals(HistoryType.OUTBOUND, history.firstValue.historyType)
                assertEquals(-4, history.firstValue.quantityChange)
                assertEquals(6, history.firstValue.quantityAfter)
                assertEquals(ReferenceType.RESERVATION, history.firstValue.referenceType)
                assertEquals(77L, history.firstValue.referenceId)
                assertEquals(IdempotencyKey.of(key), history.firstValue.idempotencyKey)
                assertEquals(RequesterService.of("svc-wms"), history.firstValue.requesterService)
                val event = argumentCaptor<InventoryEvent>()
                verifyBlocking(publisher) { publish(event.capture()) }
                assertEquals(InventoryEventType.DECREASED, event.firstValue.eventType)
                assertEquals(-4, event.firstValue.quantityChange)
                assertEquals(6, event.firstValue.quantityAfter)
            }

        @Test
        fun `결품은 예약 수량만 되돌리고 총 수량과 이력은 그대로다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.release(7L, 3)).thenReturn(inventory(7L, 10))

                val result: ShipResult = service.ship(command(ShipInventoryCommand.Item(7L, 0, 3)))

                assertEquals(listOf(ShipResult.Item(7L, null)), result.items)
                verifyBlocking(inventoryRepository, never()) { ship(any(), any()) }
                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
                verifyBlocking(publisher, never()) { publish(any()) }
            }

        @Test
        fun `일부 결품이면 출고분은 차감하고 결품분은 되돌린다`() =
            runBlocking<Unit> {
                stubShip(5L, 6)
                whenever(inventoryRepository.release(5L, 1)).thenReturn(inventory(5L, 6))

                val result: ShipResult = service.ship(command(ShipInventoryCommand.Item(5L, 3, 1)))

                assertEquals(listOf(ShipResult.Item(5L, 6)), result.items)
                verifyBlocking(inventoryRepository) { ship(5L, 3) }
                verifyBlocking(inventoryRepository) { release(5L, 1) }
            }

        @Test
        fun `재고 행은 ID 오름차순으로 갱신하고 결과도 그 순서다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.ship(any(), any())).thenAnswer { inventory(it.getArgument<Long>(0), 10) }
                whenever(inventoryHistoryRepository.save(any())).thenAnswer { it.getArgument<InventoryHistory>(0) }

                val result: ShipResult =
                    service.ship(
                        command(
                            ShipInventoryCommand.Item(9L, 1, 0),
                            ShipInventoryCommand.Item(3L, 1, 0),
                            ShipInventoryCommand.Item(7L, 1, 0),
                        ),
                    )

                assertEquals(listOf(3L, 7L, 9L), result.items.map { it.inventoryId })
                inOrder(inventoryRepository) {
                    verify(inventoryRepository).ship(3L, 1)
                    verify(inventoryRepository).ship(7L, 1)
                    verify(inventoryRepository).ship(9L, 1)
                }
            }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `예약 수량이 모자라면 예외가 그대로 나가 호출자가 롤백한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.ship(5L, 4)).thenThrow(InsufficientReservedQuantityException())

                assertThrows<InsufficientReservedQuantityException> { service.ship(command(ShipInventoryCommand.Item(5L, 4, 0))) }

                verifyBlocking(inventoryHistoryRepository, never()) { save(any()) }
            }

        @Test
        fun `같은 멱등 키의 이력이 이미 있으면 중복 키 예외가 그대로 나간다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.ship(5L, 4)).thenReturn(inventory(5L, 6))
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())

                assertThrows<DuplicateIdempotencyKeyException> { service.ship(command(ShipInventoryCommand.Item(5L, 4, 0))) }

                verifyBlocking(publisher, never()) { publish(any()) }
            }

        @Test
        fun `멱등 키와 요청 주체의 형식 오류는 아무것도 쓰기 전에 거부한다`() =
            runBlocking<Unit> {
                val badKey: InvalidDomainValueException =
                    assertThrows {
                        runBlocking {
                            service.ship(
                                ShipInventoryCommand(listOf(ShipInventoryCommand.Item(5L, 1, 0)), " ", 77L, "svc"),
                            )
                        }
                    }
                val badRequester: InvalidDomainValueException =
                    assertThrows {
                        runBlocking {
                            service.ship(
                                ShipInventoryCommand(listOf(ShipInventoryCommand.Item(5L, 1, 0)), key, 77L, " "),
                            )
                        }
                    }

                assertEquals(CommonErrorCode.INVALID_IDEMPOTENCY_KEY, badKey.errorCode)
                assertEquals(CommonErrorCode.INVALID_REQUESTER_SERVICE, badRequester.errorCode)
                verifyBlocking(inventoryRepository, never()) { ship(any(), any()) }
            }

        @Test
        fun `명령은 비어 있거나 ID와 수량이 잘못되었거나 행이 중복되면 거부한다`() {
            val invalid: List<() -> ShipInventoryCommand> =
                listOf(
                    { ShipInventoryCommand(emptyList(), key, 77L, "svc") },
                    { command(ShipInventoryCommand.Item(0L, 1, 0)) },
                    { command(ShipInventoryCommand.Item(5L, -1, 1)) },
                    { command(ShipInventoryCommand.Item(5L, 1, -1)) },
                    { command(ShipInventoryCommand.Item(5L, 0, 0)) },
                    { command(ShipInventoryCommand.Item(5L, 1, 0), ShipInventoryCommand.Item(5L, 2, 0)) },
                    { ShipInventoryCommand(listOf(ShipInventoryCommand.Item(5L, 1, 0)), key, 0L, "svc") },
                )
            invalid.forEach { build ->
                val e: InvalidDomainValueException = assertThrows { build() }
                assertEquals(InventoryErrorCode.INVALID_SHIP_REQUEST, e.errorCode)
            }
        }
    }

    @Nested
    inner class `처리 후 수량 조회` {
        private fun history(
            inventoryId: Long,
            type: HistoryType,
            referenceType: ReferenceType,
            referenceId: Long,
            after: Int,
        ): InventoryHistory =
            InventoryHistory.reconstitute(
                inventoryId * 10,
                inventoryId,
                type,
                if (type == HistoryType.OUTBOUND) -1 else 1,
                after,
                referenceType,
                referenceId,
                IdempotencyKey.of(key),
                RequesterService.of("svc-wms"),
                java.time.LocalDateTime.of(2026, 10, 8, 9, 0),
            )

        @Test
        fun `같은 키의 같은 예약 출고 이력만 재고 행별 처리 후 수량으로 돌려준다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(
                    listOf(
                        history(5L, HistoryType.OUTBOUND, ReferenceType.RESERVATION, 77L, 6),
                        history(7L, HistoryType.OUTBOUND, ReferenceType.RESERVATION, 77L, 9),
                        history(8L, HistoryType.OUTBOUND, ReferenceType.RESERVATION, 78L, 1),
                        history(9L, HistoryType.INBOUND, ReferenceType.INBOUND_ITEM, 77L, 1),
                    ),
                )

                assertEquals(mapOf(5L to 6, 7L to 9), service.getQuantitiesAfter(key, 77L))
            }

        @Test
        fun `이력이 없으면 빈 맵이다`() =
            runBlocking<Unit> {
                whenever(inventoryHistoryRepository.findAllByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(emptyList())

                assertEquals(emptyMap<Long, Int>(), service.getQuantitiesAfter(key, 77L))
            }
    }
}
