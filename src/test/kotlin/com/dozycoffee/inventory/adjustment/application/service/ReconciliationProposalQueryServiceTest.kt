package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.SearchReconciliationProposalsQuery
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
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
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class ReconciliationProposalQueryServiceTest {
    @Mock
    private lateinit var stockAdjustmentRepository: StockAdjustmentRepository

    @Mock
    private lateinit var getLotUseCase: GetLotUseCase

    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var adjustmentPolicy: AdjustmentPolicy

    private lateinit var service: ReconciliationProposalQueryService

    @BeforeEach
    fun setUp() {
        service = ReconciliationProposalQueryService(stockAdjustmentRepository, getLotUseCase, getProductUseCase, adjustmentPolicy)
    }

    private fun proposal(
        id: Long,
        warehouseId: Long = 10L,
        lotId: Long = 7L,
        change: Int = -8,
        type: AdjustmentType = AdjustmentType.RECONCILIATION,
        status: AdjustmentStatus = AdjustmentStatus.PENDING,
    ): StockAdjustment =
        StockAdjustment.reconstitute(
            id,
            warehouseId,
            type,
            status,
            null,
            RequesterService.of("system"),
            null,
            null,
            IdempotencyKey.of("reconciliation-$id"),
            listOf(StockAdjustmentItem.reconstitute(id * 10, null, lotId, QualityStatus.NORMAL, change, 100, 100 + change)),
            5L,
        )

    private suspend fun stubLotsAndThreshold(
        vararg lots: LotResult,
        threshold: Int = 100,
    ) {
        whenever(getLotUseCase.getByIds(lots.map { it.lotId }.toSet())).thenReturn(lots.toList())
        lots.map { it.productId }.toSet().forEach {
            whenever(
                getProductUseCase.getById(it),
            ).thenReturn(ProductResult(it, "P-$it", "원두", ProductCategory.BEAN, "KG", null, ProductStatus.ACTIVE))
        }
        whenever(adjustmentPolicy.approvalThresholdFor("BEAN")).thenReturn(threshold)
    }

    @Nested
    inner class `상세` {
        @Test
        fun `보정안에 Lot 정보와 임계치 초과 여부를 붙여 준다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.findById(1L)).thenReturn(proposal(1L, change = -8))
                stubLotsAndThreshold(LotResult(7L, 100L, "LOT-A", null, null), threshold = 7)

                val result: ReconciliationProposalResult = service.getById(1L, WarehouseScope.All)

                assertEquals(1L, result.stockAdjustmentId)
                assertEquals(100L, result.productId)
                assertEquals("LOT-A", result.lotNumber)
                assertEquals(-8, result.quantityChange)
                assertEquals(100, result.inventoryQuantity)
                assertEquals(92, result.wmsQuantity)
                assertEquals(true, result.requiresAdmin)
                assertEquals(AdjustmentStatus.PENDING, result.status)
            }

        @Test
        fun `없거나 실사 조정이면 없는 것으로 보고 범위 밖 창고는 거부한다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.findById(1L)).thenReturn(null)
                whenever(
                    stockAdjustmentRepository.findById(2L),
                ).thenReturn(proposal(2L, type = AdjustmentType.AUDIT, status = AdjustmentStatus.APPLIED))
                whenever(stockAdjustmentRepository.findById(3L)).thenReturn(proposal(3L, warehouseId = 20L))

                assertThrows<AdjustmentNotFoundException> { service.getById(1L, WarehouseScope.All) }
                assertThrows<AdjustmentNotFoundException> { service.getById(2L, WarehouseScope.All) }
                assertThrows<WarehouseAccessDeniedException> { service.getById(3L, WarehouseScope.Only(setOf(10L))) }
                verifyNoInteractions(getLotUseCase)
            }
    }

    @Nested
    inner class `목록` {
        @Test
        fun `조건을 저장소에 넘기고 보정안마다 Lot 정보를 붙여 페이지로 준다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.searchReconciliations(null, AdjustmentStatus.PENDING, 40L, 20))
                    .thenReturn(listOf(proposal(2L, lotId = 8L, change = 3), proposal(1L, lotId = 7L, change = -200)))
                whenever(stockAdjustmentRepository.countReconciliations(null, AdjustmentStatus.PENDING)).thenReturn(43L)
                stubLotsAndThreshold(LotResult(7L, 100L, "LOT-A", null, null), LotResult(8L, 100L, "LOT-B", null, null))

                val result: PageResult<ReconciliationProposalResult> =
                    service.search(SearchReconciliationProposalsQuery(null, AdjustmentStatus.PENDING, 2, 20, WarehouseScope.All))

                assertEquals(listOf(2L, 1L), result.items.map { it.stockAdjustmentId })
                assertEquals(listOf("LOT-B", "LOT-A"), result.items.map { it.lotNumber })
                assertEquals(listOf(false, true), result.items.map { it.requiresAdmin })
                assertEquals(43L, result.totalElements)
                assertEquals(3, result.totalPages)
            }

        @Test
        fun `창고 관리자가 창고를 생략하면 배정된 창고로 제한하고 배정되지 않은 창고를 지정하면 거부한다`() =
            runBlocking<Unit> {
                val scope = WarehouseScope.Only(setOf(10L, 30L))
                whenever(stockAdjustmentRepository.searchReconciliations(setOf(10L, 30L), null, 0L, 20)).thenReturn(emptyList())
                whenever(stockAdjustmentRepository.countReconciliations(setOf(10L, 30L), null)).thenReturn(0L)

                val result = service.search(SearchReconciliationProposalsQuery(null, null, 0, 20, scope))

                assertEquals(0, result.totalPages)
                assertThrows<WarehouseAccessDeniedException> {
                    service.search(SearchReconciliationProposalsQuery(20L, null, 0, 20, scope))
                }
                verifyBlocking(getLotUseCase, never()) { getByIds(org.mockito.kotlin.any()) }
            }

        @Test
        fun `조건이 올바르지 않으면 거부한다`() {
            listOf(
                { SearchReconciliationProposalsQuery(0L, null, 0, 20, WarehouseScope.All) },
                { SearchReconciliationProposalsQuery(null, null, -1, 20, WarehouseScope.All) },
                { SearchReconciliationProposalsQuery(null, null, 0, 0, WarehouseScope.All) },
                { SearchReconciliationProposalsQuery(null, null, 0, 101, WarehouseScope.All) },
            ).forEach { create ->
                val e: InvalidDomainValueException = assertThrows { create() }
                assertEquals(AdjustmentErrorCode.INVALID_RECONCILIATION_QUERY.code, e.errorCode.code)
            }
            SearchReconciliationProposalsQuery(null, null, 0, 100, WarehouseScope.All)
        }
    }
}
