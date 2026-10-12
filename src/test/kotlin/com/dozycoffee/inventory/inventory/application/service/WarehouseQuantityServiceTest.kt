package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRow
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class WarehouseQuantityServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    private lateinit var service: WarehouseQuantityService

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 12, 9, 0)

    @BeforeEach
    fun setUp() {
        service = WarehouseQuantityService(inventoryRepository)
    }

    private fun row(
        id: Long,
        quantity: Int,
        reserved: Int,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): InventoryRow =
        InventoryRow(id, 10L, 100L, 7L, "LOT-A", null, null, LotStatus.NORMAL, quality, quantity, reserved, false, null, null, at, at)

    @Test
    fun `재고 행의 총 수량을 키와 함께 준다(예약 수량은 뺀 값이 아니라 총 수량이다)`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findRowsByWarehouse(10L, null, null))
                .thenReturn(listOf(row(1L, 20, 5), row(2L, 0, 0, QualityStatus.DEFECTIVE)))

            val result: List<WarehouseQuantityResult> = service.getQuantities(10L, null, null)

            assertEquals(
                listOf(
                    WarehouseQuantityResult(1L, 100L, 7L, "LOT-A", QualityStatus.NORMAL, 20),
                    WarehouseQuantityResult(2L, 100L, 7L, "LOT-A", QualityStatus.DEFECTIVE, 0),
                ),
                result,
            )
        }

    @Test
    fun `상품과 Lot 번호 조건을 저장소에 넘긴다`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findRowsByWarehouse(10L, 100L, "LOT-A")).thenReturn(listOf(row(1L, 20, 0)))

            assertEquals(1, service.getQuantities(10L, 100L, "LOT-A").size)
        }

    @Test
    fun `재고 행이 있는 창고 ID를 그대로 준다`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findWarehouseIds()).thenReturn(listOf(10L, 20L))

            assertEquals(listOf(10L, 20L), service.findWarehouseIds())
        }
}
