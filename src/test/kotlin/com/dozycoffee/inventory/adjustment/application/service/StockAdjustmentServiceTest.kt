package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.ApprovalRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAdjustedQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class StockAdjustmentServiceTest {
    @Mock
    private lateinit var getLotUseCase: GetLotUseCase

    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var adjustInventoryUseCase: AdjustInventoryUseCase

    @Mock
    private lateinit var getAdjustedQuantitiesUseCase: GetAdjustedQuantitiesUseCase

    @Mock
    private lateinit var stockAdjustmentRepository: StockAdjustmentRepository

    private lateinit var service: StockAdjustmentService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 10, 12, 0)
    private val key: String = "wms-audit-1"

    /** 카테고리별 임계치: BEAN 20, 그 외 100 */
    private val policy: AdjustmentPolicy = AdjustmentPolicy { category -> if (category == "BEAN") 20 else 100 }

    @BeforeEach
    fun setUp() {
        service =
            StockAdjustmentService(
                getLotUseCase,
                getProductUseCase,
                adjustInventoryUseCase,
                getAdjustedQuantitiesUseCase,
                stockAdjustmentRepository,
                policy,
                DirectTransactionalOperator(),
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            )
    }

    private fun item(
        productId: Long = 100L,
        lotNumber: String = "A",
        change: Int = 5,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): RequestStockAdjustmentCommand.Item = RequestStockAdjustmentCommand.Item(productId, lotNumber, quality, change)

    private fun command(
        vararg items: RequestStockAdjustmentCommand.Item,
        approvedBy: String? = null,
    ): RequestStockAdjustmentCommand = RequestStockAdjustmentCommand(10L, 77L, items.toList(), approvedBy, key, "svc-wms")

    private suspend fun stubLot(
        productId: Long,
        lotNumber: String,
        lotId: Long,
    ) {
        whenever(
            getLotUseCase.getByProductAndLotNumber(productId, lotNumber),
        ).thenReturn(LotResult(lotId, productId, lotNumber, null, null))
    }

    private suspend fun stubProduct(
        productId: Long,
        category: ProductCategory,
    ) {
        whenever(getProductUseCase.getById(productId)).thenReturn(
            ProductResult(productId, "P-$productId", "상품 $productId", category, "KG", null, ProductStatus.ACTIVE),
        )
    }

    /** 저장하면 항목 ID를 1001부터 붙이고, 재고 반영은 항목마다 재고 행 ID를 2000 + 항목 ID로, 반영 후 수량을 50으로 돌려준다 */
    private suspend fun stubApply() {
        stubSave()
        stubAdjust()
    }

    private suspend fun stubSave() {
        whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null)
        whenever(stockAdjustmentRepository.save(any())).thenAnswer {
            val adjustment: StockAdjustment = it.getArgument(0)
            StockAdjustment.reconstitute(
                9L,
                adjustment.warehouseId,
                adjustment.adjustmentType,
                adjustment.status,
                adjustment.externalReferenceId,
                adjustment.requestedBy,
                adjustment.approvedBy,
                adjustment.approvedAt,
                adjustment.idempotencyKey,
                adjustment.items.mapIndexed { index, item ->
                    StockAdjustmentItem.reconstitute(1001L + index, null, item.lotId, item.qualityStatus, item.quantityChange)
                },
            )
        }
    }

    private suspend fun stubAdjust() {
        whenever(adjustInventoryUseCase.adjust(any())).thenAnswer {
            val adjust: AdjustInventoryCommand = it.getArgument(0)
            AdjustInventoryResult(adjust.items.map { item -> AdjustInventoryResult.Item(item.referenceId, 2000L + item.referenceId, 50) })
        }
    }

    @Nested
    inner class `반영` {
        @Test
        fun `임계치 이하의 변동은 승인자 없이 반영하고 항목 ID를 원인 문서로 재고에 넘긴다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubLot(101L, "B", 2L)
                stubProduct(100L, ProductCategory.BEAN)
                stubProduct(101L, ProductCategory.SYRUP)
                stubApply()

                val result: StockAdjustmentResult = service.request(command(item(100L, "A", 20), item(101L, "B", -100)))

                assertEquals(AdjustmentStatus.APPLIED, result.status)
                assertEquals(9L, result.stockAdjustmentId)
                assertEquals(listOf(1001L, 1002L), result.items.map { it.stockAdjustmentItemId })
                assertEquals(listOf(3001L, 3002L), result.items.map { it.inventoryId })
                assertEquals(listOf(50, 50), result.items.map { it.quantityAfter })
                val adjust = argumentCaptor<AdjustInventoryCommand>()
                verifyBlocking(adjustInventoryUseCase) { adjust(adjust.capture()) }
                assertEquals(10L, adjust.firstValue.warehouseId)
                assertEquals(key, adjust.firstValue.idempotencyKey)
                assertEquals("svc-wms", adjust.firstValue.requesterService)
                assertEquals(listOf(100L, 101L), adjust.firstValue.items.map { it.productId })
                assertEquals(listOf(1L, 2L), adjust.firstValue.items.map { it.lotId })
                assertEquals(listOf(1001L, 1002L), adjust.firstValue.items.map { it.referenceId })
                verifyBlocking(stockAdjustmentRepository) { assignInventoryIds(mapOf(1001L to 3001L, 1002L to 3002L)) }
            }

        @Test
        fun `요청 주체와 실사 건 ID, 창고를 조정에 기록한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                stubApply()

                service.request(command(item(change = 5)))

                val saved = argumentCaptor<StockAdjustment>()
                verifyBlocking(stockAdjustmentRepository) { save(saved.capture()) }
                assertEquals(10L, saved.firstValue.warehouseId)
                assertEquals(77L, saved.firstValue.externalReferenceId)
                assertEquals(RequesterService.of("svc-wms"), saved.firstValue.requestedBy)
                assertEquals(IdempotencyKey.of(key), saved.firstValue.idempotencyKey)
                assertEquals(null, saved.firstValue.approvedBy)
            }
    }

    @Nested
    inner class `승인` {
        @Test
        fun `카테고리 임계치를 넘는 변동이 있는데 승인자가 없으면 아무것도 저장하거나 반영하지 않고 거부한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null)

                assertThrows<ApprovalRequiredException> { service.request(command(item(100L, "A", 21))) }

                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `임계치와 같은 변동은 승인이 필요 없다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                stubApply()

                service.request(command(item(100L, "A", -20)))

                verifyBlocking(adjustInventoryUseCase) { adjust(any()) }
            }

        @Test
        fun `감소도 변동량의 절댓값으로 임계치와 비교한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null)

                assertThrows<ApprovalRequiredException> { service.request(command(item(100L, "A", -21))) }
            }

        @Test
        fun `항목 중 하나라도 임계치를 넘으면 거부한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubLot(101L, "B", 2L)
                stubProduct(100L, ProductCategory.BEAN)
                stubProduct(101L, ProductCategory.SYRUP)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null)

                assertThrows<ApprovalRequiredException> { service.request(command(item(100L, "A", 1), item(101L, "B", 101))) }
            }

        @Test
        fun `승인자가 있으면 임계치를 넘어도 반영하고 승인자와 승인 시각을 기록한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubApply()

                val result: StockAdjustmentResult = service.request(command(item(100L, "A", 500), approvedBy = "manager-7"))

                assertEquals("manager-7", result.approvedBy)
                val saved = argumentCaptor<StockAdjustment>()
                verifyBlocking(stockAdjustmentRepository) { save(saved.capture()) }
                assertEquals(now, saved.firstValue.approvedAt)
                verifyBlocking(getProductUseCase, never()) { getById(any()) }
            }

        @Test
        fun `공백뿐인 승인자는 없는 것으로 본다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null)

                assertThrows<ApprovalRequiredException> { service.request(command(item(100L, "A", 500), approvedBy = "  ")) }
            }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `등록되지 않은 Lot이면 거부하고 아무것도 저장하지 않는다`() =
            runBlocking<Unit> {
                whenever(getLotUseCase.getByProductAndLotNumber(100L, "A")).thenThrow(LotNotFoundException())

                assertThrows<LotNotFoundException> { service.request(command(item())) }

                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
            }

        @Test
        fun `재고 반영이 실패하면 예외를 그대로 알리고 항목 재고 행을 기록하지 않는다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                stubSave()
                whenever(adjustInventoryUseCase.adjust(any())).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.request(command(item(change = -5))) }

                verifyBlocking(stockAdjustmentRepository, never()) { assignInventoryIds(any()) }
            }
    }

    @Nested
    inner class `멱등` {
        private fun stored(
            warehouseId: Long = 10L,
            externalReferenceId: Long = 77L,
            change: Int = 5,
        ): StockAdjustment =
            StockAdjustment.reconstitute(
                9L,
                warehouseId,
                com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType.AUDIT,
                AdjustmentStatus.APPLIED,
                externalReferenceId,
                RequesterService.of("svc-wms"),
                null,
                null,
                IdempotencyKey.of(key),
                listOf(StockAdjustmentItem.reconstitute(1001L, 3001L, 1L, QualityStatus.NORMAL, change)),
            )

        @Test
        fun `같은 멱등 키의 같은 내용은 새로 반영하지 않고 저장된 조정과 이력으로 이전 결과를 반환한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(stored())
                whenever(getAdjustedQuantitiesUseCase.getQuantitiesAfter(key)).thenReturn(mapOf(1001L to 42))

                val result: StockAdjustmentResult = service.request(command(item(100L, "A", 5)))

                assertEquals(9L, result.stockAdjustmentId)
                assertEquals(listOf(3001L), result.items.map { it.inventoryId })
                assertEquals(listOf(42), result.items.map { it.quantityAfter })
                verifyBlocking(stockAdjustmentRepository, never()) { save(any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `재요청은 승인 임계치를 다시 확인하지 않는다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(stored(change = 500))
                whenever(getAdjustedQuantitiesUseCase.getQuantitiesAfter(key)).thenReturn(mapOf(1001L to 42))

                service.request(command(item(100L, "A", 500)))

                verifyBlocking(getProductUseCase, never()) { getById(any()) }
            }

        @Test
        fun `같은 멱등 키에 다른 내용이 오면 거부한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(stored())

                assertThrows<IdempotencyKeyConflictException> { service.request(command(item(100L, "A", 6))) }
                assertThrows<IdempotencyKeyConflictException> {
                    stubLot(100L, "B", 1L)
                    service.request(command(item(100L, "B", 5, QualityStatus.DEFECTIVE)))
                }
            }

        @Test
        fun `동시에 같은 키가 들어와 저장이 중복으로 실패하면 먼저 처리된 결과를 반환한다`() =
            runBlocking<Unit> {
                stubLot(100L, "A", 1L)
                stubProduct(100L, ProductCategory.BEAN)
                whenever(stockAdjustmentRepository.findByIdempotencyKey(IdempotencyKey.of(key))).thenReturn(null).thenReturn(stored())
                whenever(stockAdjustmentRepository.save(any())).thenThrow(DuplicateAdjustmentKeyException())
                whenever(getAdjustedQuantitiesUseCase.getQuantitiesAfter(key)).thenReturn(mapOf(1001L to 42))

                val result: StockAdjustmentResult = service.request(command(item(100L, "A", 5)))

                assertEquals(9L, result.stockAdjustmentId)
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }
    }
}
