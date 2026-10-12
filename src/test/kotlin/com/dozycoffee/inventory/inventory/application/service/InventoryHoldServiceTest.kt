package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class InventoryHoldServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    private lateinit var service: InventoryHoldService

    private val heldAt: LocalDateTime = LocalDateTime.of(2026, 10, 12, 9, 30, 0, 123_456_000)

    @BeforeEach
    fun setUp() {
        // 나노초까지 있는 시각이 마이크로초로 잘려 저장소에 전달되는지 본다
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-12T09:30:00.123456789Z"), ZoneOffset.UTC)
        service = InventoryHoldService(inventoryRepository, clock)
    }

    @Test
    fun `재고 행을 ID 오름차순으로 마이크로초 시각과 함께 보류한다`() =
        runBlocking<Unit> {
            service.hold(setOf(9L, 3L, 5L), "피킹 불가 보고: 예약 1")

            val order: InOrder = inOrder(inventoryRepository)
            order.verify(inventoryRepository).holdAllocation(3L, "피킹 불가 보고: 예약 1", heldAt)
            order.verify(inventoryRepository).holdAllocation(5L, "피킹 불가 보고: 예약 1", heldAt)
            order.verify(inventoryRepository).holdAllocation(9L, "피킹 불가 보고: 예약 1", heldAt)
            order.verifyNoMoreInteractions()
        }

    @Test
    fun `재고 행이 없으면 저장소를 호출하지 않는다`() =
        runBlocking<Unit> {
            service.hold(emptySet(), "사유")

            verifyNoInteractions(inventoryRepository)
        }
}
