package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.command.GetAvailabilityQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AvailabilityResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ProductAvailability
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseAvailability
import com.dozycoffee.inventory.inventory.application.port.out.AvailabilityRow
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class AvailabilityServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    private lateinit var service: AvailabilityService

    @BeforeEach
    fun setUp() {
        service = AvailabilityService(inventoryRepository)
    }

    @Nested
    inner class `창고를 지정하지 않으면` {
        @Test
        fun `상품별로 묶고 재고 행이 있는 창고를 오름차순으로 보여 주며 합계를 계산한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findAvailability(setOf(2L, 1L), null)).thenReturn(
                    listOf(
                        AvailabilityRow(20L, 1L, 20L),
                        AvailabilityRow(10L, 1L, 100L),
                        AvailabilityRow(10L, 2L, 7L),
                    ),
                )

                val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(2L, 1L), null))

                assertEquals(
                    AvailabilityResult(
                        listOf(
                            ProductAvailability(1L, 120L, listOf(WarehouseAvailability(10L, 100L), WarehouseAvailability(20L, 20L))),
                            ProductAvailability(2L, 7L, listOf(WarehouseAvailability(10L, 7L))),
                        ),
                    ),
                    result,
                )
            }

        @Test
        fun `재고가 전혀 없는 상품도 합계 0과 빈 창고 목록으로 나온다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findAvailability(setOf(1L, 3L), null)).thenReturn(listOf(AvailabilityRow(10L, 1L, 5L)))

                val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(1L, 3L), null))

                assertEquals(ProductAvailability(3L, 0L, emptyList()), result.products[1])
            }

        @Test
        fun `가용 수량이 0인 창고도 재고 행이 있으면 보여 준다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findAvailability(setOf(1L), null)).thenReturn(listOf(AvailabilityRow(10L, 1L, 0L)))

                val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(1L), null))

                assertEquals(listOf(WarehouseAvailability(10L, 0L)), result.products.single().warehouses)
            }
    }

    @Nested
    inner class `창고를 지정하면` {
        @Test
        fun `재고 행이 없는 창고도 0으로 채워 지정한 창고를 모두 보여 준다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findAvailability(setOf(1L), setOf(30L, 10L))).thenReturn(listOf(AvailabilityRow(10L, 1L, 4L)))

                val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(1L), setOf(30L, 10L)))

                assertEquals(
                    ProductAvailability(1L, 4L, listOf(WarehouseAvailability(10L, 4L), WarehouseAvailability(30L, 0L))),
                    result.products.single(),
                )
            }

        @Test
        fun `재고가 없는 상품은 지정한 창고마다 0이다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findAvailability(setOf(9L), setOf(10L, 20L))).thenReturn(emptyList())

                val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(9L), setOf(10L, 20L)))

                assertEquals(
                    ProductAvailability(9L, 0L, listOf(WarehouseAvailability(10L, 0L), WarehouseAvailability(20L, 0L))),
                    result.products.single(),
                )
            }
    }

    @Test
    fun `합계는 Int 범위를 넘어도 Long으로 계산한다`() =
        runBlocking<Unit> {
            whenever(inventoryRepository.findAvailability(setOf(1L), null)).thenReturn(
                listOf(AvailabilityRow(10L, 1L, Int.MAX_VALUE.toLong()), AvailabilityRow(20L, 1L, Int.MAX_VALUE.toLong())),
            )

            val result: AvailabilityResult = service.getAvailability(GetAvailabilityQuery(setOf(1L), null))

            assertEquals(Int.MAX_VALUE.toLong() * 2, result.products.single().totalAvailableQuantity)
        }
}
