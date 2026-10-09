package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryLotInfo
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class InventoryLotServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    private lateinit var service: InventoryLotService

    @BeforeEach
    fun setUp() {
        service = InventoryLotService(inventoryRepository)
    }

    @Test
    fun `재고 행 ID 오름차순으로 Lot 정보를 반환하고 유통기한이 없으면 null이다`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findLotInfos(setOf(9L, 5L))).thenReturn(
                listOf(
                    InventoryLotInfo(9L, 3L, "LOT-C", null),
                    InventoryLotInfo(5L, 2L, "LOT-B", LocalDate.of(2027, 1, 1)),
                ),
            )

            val result: List<InventoryLotResult> = service.getLots(setOf(9L, 5L))

            assertEquals(
                listOf(InventoryLotResult(5L, 2L, "LOT-B", LocalDate.of(2027, 1, 1)), InventoryLotResult(9L, 3L, "LOT-C", null)),
                result,
            )
        }

    @Test
    fun `재고 행이 없으면 저장소를 조회하지 않고 빈 목록을 반환한다`() =
        runBlocking<Unit> {
            assertEquals(emptyList<InventoryLotResult>(), service.getLots(emptySet()))

            verifyNoInteractions(inventoryRepository)
        }

    @Test
    fun `없는 재고 행은 결과에 나오지 않는다`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findLotInfos(setOf(5L, 99L))).thenReturn(listOf(InventoryLotInfo(5L, 2L, "LOT-B", null)))

            assertEquals(listOf(5L), service.getLots(setOf(5L, 99L)).map { it.inventoryId })
        }
}
