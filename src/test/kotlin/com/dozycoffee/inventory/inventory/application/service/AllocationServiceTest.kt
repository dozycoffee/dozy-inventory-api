package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.application.port.out.AllocationCandidate
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.exception.AllocationConflictException
import com.dozycoffee.inventory.inventory.domain.exception.AllocationHeldException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotReservableException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class AllocationServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    private val today: LocalDate = LocalDate.of(2026, 10, 7)
    private lateinit var service: AllocationService

    @BeforeEach
    fun setUp() {
        val clock: Clock = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneId.of("Asia/Seoul"))
        service = AllocationService(inventoryRepository, clock)
    }

    private fun candidate(
        productId: Long,
        inventoryId: Long,
        available: Int,
        expiration: LocalDate? = LocalDate.of(2027, 1, 1),
    ): AllocationCandidate = AllocationCandidate(productId, inventoryId, inventoryId + 1000, expiration, available)

    private fun command(vararg items: Pair<Long, Int>): AllocateInventoryCommand =
        AllocateInventoryCommand(10L, items.map { AllocateInventoryCommand.Item(it.first, it.second) })

    private suspend fun candidates(vararg candidates: AllocationCandidate) {
        whenever(inventoryRepository.findAllocationCandidates(eq(10L), any(), eq(today))).thenReturn(candidates.toList())
    }

    private suspend fun reserveReturnsNormally() {
        whenever(inventoryRepository.reserve(any(), any())).thenReturn(Inventory.create(10L, 1L, 1L, QualityStatus.NORMAL))
    }

    @Nested
    inner class `할당 계획` {
        @Test
        fun `유통기한이 이른 Lot부터 채우고 마지막 Lot은 필요한 만큼만 잡는다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 5L, 4), candidate(1L, 7L, 10))
                reserveReturnsNormally()

                val result: AllocationResult = service.allocate(command(1L to 6))

                assertEquals(
                    listOf(
                        AllocationResult.LotAllocation(5L, 1005L, LocalDate.of(2027, 1, 1), 4),
                        AllocationResult.LotAllocation(7L, 1007L, LocalDate.of(2027, 1, 1), 2),
                    ),
                    result.items.single().lots,
                )
                verify(inventoryRepository).reserve(5L, 4)
                verify(inventoryRepository).reserve(7L, 2)
            }

        @Test
        fun `여러 상품은 요청한 순서로 결과를 주고 갱신은 상품과 무관하게 행 ID 오름차순이다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 9L, 5), candidate(2L, 3L, 5), candidate(2L, 8L, 5))
                reserveReturnsNormally()

                val result: AllocationResult = service.allocate(command(1L to 5, 2L to 7))

                assertEquals(listOf(1L, 2L), result.items.map { it.productId })
                assertEquals(listOf(3L, 8L), result.items[1].lots.map { it.inventoryId })
                inOrder(inventoryRepository) {
                    verify(inventoryRepository).reserve(3L, 5)
                    verify(inventoryRepository).reserve(8L, 2)
                    verify(inventoryRepository).reserve(9L, 5)
                }
            }

        @Test
        fun `한 상품이라도 모자라면 어떤 행도 갱신하지 않고 가용 수량 부족으로 실패한다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 1L, 10), candidate(2L, 2L, 3))

                assertThrows<InsufficientAvailableQuantityException> { service.allocate(command(1L to 5, 2L to 4)) }

                verify(inventoryRepository, never()).reserve(any(), any())
            }

        @Test
        fun `후보가 하나도 없으면 가용 수량 부족이다`() =
            runBlocking<Unit> {
                candidates()

                assertThrows<InsufficientAvailableQuantityException> { service.allocate(command(1L to 1)) }
            }
    }

    @Nested
    inner class `경합` {
        @Test
        fun `갱신 중 가용 수량을 잃으면 경합 패배로 알린다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 1L, 5), candidate(1L, 2L, 5))
                reserveReturnsNormally()
                whenever(inventoryRepository.reserve(2L, 5)).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<AllocationConflictException> { service.allocate(command(1L to 10)) }
            }

        @Test
        fun `후보가 그 사이 보류되거나 불량 처리되어도 경합 패배로 알린다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 1L, 5))
                doThrow(AllocationHeldException()).doThrow(InventoryNotReservableException()).whenever(inventoryRepository).reserve(1L, 5)

                assertThrows<AllocationConflictException> { service.allocate(command(1L to 5)) }
                assertThrows<AllocationConflictException> { service.allocate(command(1L to 5)) }
            }

        @Test
        fun `경합이 아닌 오류는 그대로 던진다`() =
            runBlocking<Unit> {
                candidates(candidate(1L, 1L, 5))
                whenever(inventoryRepository.reserve(1L, 5)).thenThrow(InventoryNotFoundException())

                assertThrows<InventoryNotFoundException> { service.allocate(command(1L to 5)) }
            }
    }

    @Nested
    inner class `명령 검증` {
        @Test
        fun `창고와 상품 ID는 양수이고 수량은 1 이상이며 상품은 중복될 수 없다`() {
            val invalid: List<() -> AllocateInventoryCommand> =
                listOf(
                    { AllocateInventoryCommand(0L, listOf(AllocateInventoryCommand.Item(1L, 1))) },
                    { AllocateInventoryCommand(10L, emptyList()) },
                    { command(1L to 0) },
                    { command(0L to 1) },
                    { command(1L to 1, 1L to 2) },
                    { AllocateInventoryCommand(10L, (1L..101L).map { AllocateInventoryCommand.Item(it, 1) }) },
                )
            invalid.forEach { build ->
                val e: InvalidDomainValueException = assertThrows { build() }
                assertEquals(InventoryErrorCode.INVALID_ALLOCATION_REQUEST, e.errorCode)
            }
        }
    }
}
