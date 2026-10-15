package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventorySummariesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventorySummaryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRow
import com.dozycoffee.inventory.inventory.application.port.out.InventorySearchCondition
import com.dozycoffee.inventory.inventory.application.port.out.InventorySummaryRow
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.product.application.port.`in`.GetProductsUseCase
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class InventoryQueryServiceTest {
    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    @Mock
    private lateinit var getProductsUseCase: GetProductsUseCase

    private lateinit var service: InventoryQueryService

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 14, 9, 0)

    @BeforeEach
    fun setUp() {
        service = InventoryQueryService(inventoryRepository, inventoryHistoryRepository, getProductsUseCase)
    }

    private val bean: ProductResult = ProductResult(100L, "BEAN-001", "에티오피아 원두", ProductCategory.BEAN, "KG", 180, ProductStatus.ACTIVE)

    private suspend fun stubProducts(vararg products: ProductResult) {
        whenever(getProductsUseCase.getByIds(products.map { it.productId }.toSet())).thenReturn(products.associateBy { it.productId })
    }

    private fun row(
        id: Long,
        quantity: Int = 10,
        reserved: Int = 4,
    ): InventoryRow =
        InventoryRow(
            id,
            10L,
            100L,
            7L,
            "LOT-A",
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 1, 1),
            LotStatus.NORMAL,
            QualityStatus.NORMAL,
            quantity,
            reserved,
            false,
            null,
            null,
            at,
            at,
        )

    private fun query(
        page: Int = 0,
        size: Int = 20,
        warehouseId: Long? = null,
        scope: WarehouseScope = WarehouseScope.All,
    ): SearchInventoriesQuery = SearchInventoriesQuery(warehouseId, null, QualityStatus.DEFECTIVE, null, true, page, size, scope)

    @Nested
    inner class `재고 현황` {
        @Test
        fun `조건과 페이지 위치를 저장소에 넘기고 가용 수량을 계산해 페이지 결과로 만든다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(setOf(10L), null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.search(condition, 40L, 20)).thenReturn(listOf(row(1L), row(2L, quantity = 5, reserved = 5)))
                stubProducts(bean)
                whenever(inventoryRepository.count(condition)).thenReturn(43L)

                val result: PageResult<InventoryResult> = service.search(query(page = 2, warehouseId = 10L))

                assertEquals(listOf(6, 0), result.items.map { it.availableQuantity })
                assertEquals(2, result.page)
                assertEquals(20, result.size)
                assertEquals(43L, result.totalElements)
                assertEquals(3, result.totalPages)
                assertEquals("LOT-A", result.items.first().lotNumber)
                assertEquals(listOf("BEAN-001", "BEAN-001"), result.items.map { it.productCode })
                assertEquals("에티오피아 원두", result.items.first().productName)
                assertEquals("KG", result.items.first().unit)
            }

        @Test
        fun `결과가 없으면 전체 페이지 수는 0이다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(null, null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.search(condition, 0L, 20)).thenReturn(emptyList())
                whenever(inventoryRepository.count(condition)).thenReturn(0L)

                assertEquals(0, service.search(query()).totalPages)
            }

        @Test
        fun `창고 관리자가 창고를 생략하면 배정된 창고로 제한한다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(setOf(10L, 30L), null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.search(condition, 0L, 20)).thenReturn(listOf(row(1L)))
                whenever(inventoryRepository.count(condition)).thenReturn(1L)
                stubProducts(bean)

                val result: PageResult<InventoryResult> = service.search(query(scope = WarehouseScope.Only(setOf(10L, 30L))))

                assertEquals(1, result.items.size)
            }

        @Test
        fun `창고 관리자가 배정되지 않은 창고를 지정하면 거부하고 조회하지 않는다`() =
            runBlocking<Unit> {
                assertThrows<WarehouseAccessDeniedException> {
                    service.search(query(warehouseId = 20L, scope = WarehouseScope.Only(setOf(10L, 30L))))
                }

                verifyNoInteractions(inventoryRepository)
            }

        @Test
        fun `배정된 창고를 지정하면 그 창고만 조회한다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(setOf(30L), null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.search(condition, 0L, 20)).thenReturn(emptyList())
                whenever(inventoryRepository.count(condition)).thenReturn(0L)

                service.search(query(warehouseId = 30L, scope = WarehouseScope.Only(setOf(10L, 30L))))
            }

        @Test
        fun `ID가 양수가 아니거나 페이지가 음수이거나 크기가 범위를 벗어나면 거부한다`() {
            listOf(
                { SearchInventoriesQuery(0L, null, null, null, null, 0, 20, WarehouseScope.All) },
                { SearchInventoriesQuery(null, 0L, null, null, null, 0, 20, WarehouseScope.All) },
                { SearchInventoriesQuery(null, null, null, null, null, -1, 20, WarehouseScope.All) },
                { SearchInventoriesQuery(null, null, null, null, null, 0, 0, WarehouseScope.All) },
                { SearchInventoriesQuery(null, null, null, null, null, 0, 101, WarehouseScope.All) },
            ).forEach { create ->
                val e: InvalidDomainValueException = assertThrows { create() }
                assertEquals(InventoryErrorCode.INVALID_INVENTORY_QUERY.code, e.errorCode.code)
            }
            SearchInventoriesQuery(null, null, null, null, null, 0, 100, WarehouseScope.All)
        }
    }

    private fun summaryRow(
        productId: Long,
        quantity: Long = 30L,
        reserved: Long = 8L,
    ): InventorySummaryRow = InventorySummaryRow(productId, 2, 3, quantity, reserved, 4L, 5L, 6L, 7L)

    private fun summaryQuery(
        page: Int = 0,
        size: Int = 20,
        warehouseId: Long? = null,
        scope: WarehouseScope = WarehouseScope.All,
    ): SearchInventorySummariesQuery =
        SearchInventorySummariesQuery(warehouseId, null, QualityStatus.DEFECTIVE, null, true, page, size, scope)

    @Nested
    inner class `상품별 요약` {
        @Test
        fun `조건과 페이지 위치를 저장소에 넘기고 상품 정보를 채워 가용 수량을 계산한다`() =
            runBlocking<Unit> {
                val syrup = ProductResult(200L, "SYR-001", "바닐라 시럽", ProductCategory.SYRUP, "L", null, ProductStatus.ACTIVE)
                val condition = InventorySearchCondition(setOf(10L), null, QualityStatus.DEFECTIVE, null, true)
                whenever(
                    inventoryRepository.searchSummaries(condition, 20L, 10),
                ).thenReturn(listOf(summaryRow(100L), summaryRow(200L, 5L, 5L)))
                whenever(inventoryRepository.countSummaries(condition)).thenReturn(21L)
                stubProducts(bean, syrup)

                val result: PageResult<InventorySummaryResult> =
                    service.searchSummaries(
                        summaryQuery(page = 2, size = 10, warehouseId = 10L),
                    )

                assertEquals(listOf("BEAN-001", "SYR-001"), result.items.map { it.productCode })
                assertEquals(listOf("에티오피아 원두", "바닐라 시럽"), result.items.map { it.productName })
                assertEquals(listOf("KG", "L"), result.items.map { it.unit })
                assertEquals(listOf(22L, 0L), result.items.map { it.availableQuantity })
                assertEquals(2, result.page)
                assertEquals(21L, result.totalElements)
                assertEquals(3, result.totalPages)
            }

        @Test
        fun `합계와 구분별 수량과 창고 수를 그대로 전달한다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(null, null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.searchSummaries(condition, 0L, 20)).thenReturn(listOf(summaryRow(100L)))
                whenever(inventoryRepository.countSummaries(condition)).thenReturn(1L)
                stubProducts(bean)

                val item: InventorySummaryResult = service.searchSummaries(summaryQuery()).items.single()

                assertEquals(2, item.warehouseCount)
                assertEquals(3, item.rowCount)
                assertEquals(30L, item.quantity)
                assertEquals(8L, item.reservedQuantity)
                assertEquals(4L, item.defectiveQuantity)
                assertEquals(5L, item.heldQuantity)
                assertEquals(6L, item.expiredQuantity)
                assertEquals(7L, item.expiringSoonQuantity)
            }

        @Test
        fun `결과가 없으면 상품을 조회하지 않고 전체 페이지 수는 0이다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(null, null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.searchSummaries(condition, 0L, 20)).thenReturn(emptyList())
                whenever(inventoryRepository.countSummaries(condition)).thenReturn(0L)

                assertEquals(0, service.searchSummaries(summaryQuery()).totalPages)

                verifyNoInteractions(getProductsUseCase)
            }

        @Test
        fun `창고 관리자가 창고를 생략하면 배정된 창고로 제한한다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(setOf(10L, 30L), null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.searchSummaries(condition, 0L, 20)).thenReturn(listOf(summaryRow(100L)))
                whenever(inventoryRepository.countSummaries(condition)).thenReturn(1L)
                stubProducts(bean)

                assertEquals(1, service.searchSummaries(summaryQuery(scope = WarehouseScope.Only(setOf(10L, 30L)))).items.size)
            }

        @Test
        fun `창고 관리자가 배정되지 않은 창고를 지정하면 거부하고 조회하지 않는다`() =
            runBlocking<Unit> {
                assertThrows<WarehouseAccessDeniedException> {
                    service.searchSummaries(summaryQuery(warehouseId = 20L, scope = WarehouseScope.Only(setOf(10L, 30L))))
                }

                verifyNoInteractions(inventoryRepository)
                verifyNoInteractions(getProductsUseCase)
            }

        @Test
        fun `ID가 양수가 아니거나 페이지가 음수이거나 크기가 범위를 벗어나면 거부한다`() {
            listOf(
                { SearchInventorySummariesQuery(0L, null, null, null, null, 0, 20, WarehouseScope.All) },
                { SearchInventorySummariesQuery(null, 0L, null, null, null, 0, 20, WarehouseScope.All) },
                { SearchInventorySummariesQuery(null, null, null, null, null, -1, 20, WarehouseScope.All) },
                { SearchInventorySummariesQuery(null, null, null, null, null, 0, 0, WarehouseScope.All) },
                { SearchInventorySummariesQuery(null, null, null, null, null, 0, 101, WarehouseScope.All) },
            ).forEach { create ->
                val e: InvalidDomainValueException = assertThrows { create() }
                assertEquals(InventoryErrorCode.INVALID_INVENTORY_QUERY.code, e.errorCode.code)
            }
            SearchInventorySummariesQuery(null, null, null, null, null, 0, 100, WarehouseScope.All)
        }

        @Test
        fun `재고 행의 상품을 찾을 수 없으면 데이터 오류로 본다`() =
            runBlocking<Unit> {
                val condition = InventorySearchCondition(null, null, QualityStatus.DEFECTIVE, null, true)
                whenever(inventoryRepository.searchSummaries(condition, 0L, 20)).thenReturn(listOf(summaryRow(999L)))
                whenever(getProductsUseCase.getByIds(setOf(999L))).thenReturn(emptyMap())

                assertThrows<IllegalStateException> { service.searchSummaries(summaryQuery()) }
            }
    }

    @Nested
    inner class `상세` {
        @Test
        fun `재고 행과 Lot 정보를 합쳐 준다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findRowById(1L)).thenReturn(row(1L))
                stubProducts(bean)

                val result: InventoryResult = service.getById(1L, WarehouseScope.All)

                assertEquals(1L, result.inventoryId)
                assertEquals(LocalDate.of(2027, 1, 1), result.expirationDate)
                assertEquals(6, result.availableQuantity)
                assertEquals("에티오피아 원두", result.productName)
            }

        @Test
        fun `배정된 창고의 행이면 보여 주고 아니면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findRowById(1L)).thenReturn(row(1L))
                stubProducts(bean)

                assertEquals(1L, service.getById(1L, WarehouseScope.Only(setOf(10L))).inventoryId)
                assertThrows<WarehouseAccessDeniedException> { service.getById(1L, WarehouseScope.Only(setOf(20L))) }
            }

        @Test
        fun `없는 재고 행이면 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findRowById(1L)).thenReturn(null)

                assertThrows<InventoryNotFoundException> { service.getById(1L, WarehouseScope.All) }
            }
    }

    @Nested
    inner class `이력` {
        private fun history(id: Long): InventoryHistory =
            InventoryHistory.reconstitute(
                id,
                1L,
                HistoryType.INBOUND,
                5,
                15,
                ReferenceType.INBOUND_ITEM,
                77L,
                IdempotencyKey.of("k-$id"),
                RequesterService.of("svc-wms"),
                at,
            )

        private fun existing(): Inventory = Inventory.reconstitute(1L, 10L, 100L, 7L, QualityStatus.NORMAL, 10, 0, false, null, null)

        @Test
        fun `재고 행의 이력을 페이지 위치에 맞춰 조회해 결과로 만든다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findById(1L)).thenReturn(existing())
                whenever(inventoryHistoryRepository.findPageByInventoryId(1L, 10L, 5)).thenReturn(listOf(history(9L), history(8L)))
                whenever(inventoryHistoryRepository.countByInventoryId(1L)).thenReturn(12L)

                val result: PageResult<InventoryHistoryResult> = service.getHistories(1L, WarehouseScope.All, 2, 5)

                assertEquals(listOf(9L, 8L), result.items.map { it.inventoryHistoryId })
                assertEquals(HistoryType.INBOUND, result.items.first().historyType)
                assertEquals("svc-wms", result.items.first().requesterService)
                assertEquals(12L, result.totalElements)
                assertEquals(3, result.totalPages)
            }

        @Test
        fun `배정되지 않은 창고의 이력은 조회하지 않고 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findById(1L)).thenReturn(existing())

                assertThrows<WarehouseAccessDeniedException> { service.getHistories(1L, WarehouseScope.Only(setOf(20L)), 0, 20) }

                verifyNoInteractions(inventoryHistoryRepository)
            }

        @Test
        fun `없는 재고 행이면 이력을 조회하지 않고 거부한다`() =
            runBlocking<Unit> {
                whenever(inventoryRepository.findById(1L)).thenReturn(null)

                assertThrows<InventoryNotFoundException> { service.getHistories(1L, WarehouseScope.All, 0, 20) }

                verifyBlocking(inventoryHistoryRepository, never()) {
                    findPageByInventoryId(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
                }
            }

        @Test
        fun `페이지나 크기가 범위를 벗어나면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<InvalidDomainValueException> { service.getHistories(1L, WarehouseScope.All, -1, 20) }
                assertThrows<InvalidDomainValueException> { service.getHistories(1L, WarehouseScope.All, 0, 0) }
                assertThrows<InvalidDomainValueException> { service.getHistories(1L, WarehouseScope.All, 0, 101) }
                assertThrows<InvalidDomainValueException> { service.getHistories(0L, WarehouseScope.All, 0, 20) }
            }
    }
}
