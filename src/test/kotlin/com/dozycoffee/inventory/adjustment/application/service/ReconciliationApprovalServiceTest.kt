package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationAdminRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationChangedException
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetWarehouseQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustmentKind
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class ReconciliationApprovalServiceTest {
    @Mock
    private lateinit var stockAdjustmentRepository: StockAdjustmentRepository

    @Mock
    private lateinit var getLotUseCase: GetLotUseCase

    @Mock
    private lateinit var getProductUseCase: GetProductUseCase

    @Mock
    private lateinit var getWarehouseQuantitiesUseCase: GetWarehouseQuantitiesUseCase

    @Mock
    private lateinit var wmsStockPort: WmsStockPort

    @Mock
    private lateinit var adjustmentPolicy: AdjustmentPolicy

    @Mock
    private lateinit var adjustInventoryUseCase: AdjustInventoryUseCase

    private lateinit var service: ReconciliationApprovalService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 12, 10, 0)
    private val reviewer: String = "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"
    private val key: String = "reconciliation-10-20261012-7-NORMAL"

    @BeforeEach
    fun setUp() {
        service =
            ReconciliationApprovalService(
                stockAdjustmentRepository,
                getLotUseCase,
                getProductUseCase,
                getWarehouseQuantitiesUseCase,
                wmsStockPort,
                adjustmentPolicy,
                adjustInventoryUseCase,
                DirectTransactionalOperator(),
                Clock.fixed(now.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
            )
    }

    /** 창고 10, Lot 7(상품 100), 처리 대기, inventory 100 → WMS 92(변동량 −8) */
    private fun proposal(
        status: AdjustmentStatus = AdjustmentStatus.PENDING,
        type: AdjustmentType = AdjustmentType.RECONCILIATION,
        inventoryQuantity: Int = 100,
        wmsQuantity: Int = 92,
    ): StockAdjustment =
        StockAdjustment.reconstitute(
            1L,
            10L,
            type,
            status,
            null,
            RequesterService.of("system"),
            if (status == AdjustmentStatus.APPLIED) "someone" else null,
            if (status == AdjustmentStatus.APPLIED) now.minusHours(1) else null,
            IdempotencyKey.of(key),
            listOf(
                StockAdjustmentItem.reconstitute(
                    11L,
                    null,
                    7L,
                    QualityStatus.NORMAL,
                    wmsQuantity - inventoryQuantity,
                    inventoryQuantity,
                    wmsQuantity,
                ),
            ),
            5L,
        )

    private fun command(
        isAdmin: Boolean = false,
        scope: WarehouseScope = WarehouseScope.All,
    ): ReviewReconciliationCommand = ReviewReconciliationCommand(1L, reviewer, isAdmin, scope)

    private suspend fun stubBase(
        adjustments: List<StockAdjustment> = listOf(proposal()),
        threshold: Int = 100,
    ) {
        whenever(stockAdjustmentRepository.findById(1L)).thenReturn(adjustments.first(), *adjustments.drop(1).toTypedArray())
        whenever(getLotUseCase.getByIds(setOf(7L))).thenReturn(listOf(LotResult(7L, 100L, "LOT-A", null, null)))
        whenever(
            getProductUseCase.getById(100L),
        ).thenReturn(ProductResult(100L, "BEAN-1", "원두", ProductCategory.BEAN, "KG", null, ProductStatus.ACTIVE))
        whenever(adjustmentPolicy.approvalThresholdFor("BEAN")).thenReturn(threshold)
    }

    private suspend fun stubNow(
        wms: Int,
        inventory: Int,
    ) {
        whenever(
            wmsStockPort.findQuantities(10L, 100L, "LOT-A"),
        ).thenReturn(listOf(WmsStockQuantity(100L, "LOT-A", QualityStatus.NORMAL, wms)))
        whenever(getWarehouseQuantitiesUseCase.getQuantities(10L, 100L, "LOT-A"))
            .thenReturn(listOf(WarehouseQuantityResult(70L, 100L, 7L, "LOT-A", QualityStatus.NORMAL, inventory)))
    }

    private suspend fun stubApply() {
        whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(true)
        whenever(adjustInventoryUseCase.adjust(any())).thenReturn(AdjustInventoryResult(listOf(AdjustInventoryResult.Item(11L, 70L, 92))))
    }

    @Nested
    inner class `승인` {
        @Test
        fun `숫자가 그대로이면 상태를 반영 상태로 바꾸고 RECONCILIATION 이력으로 재고에 반영한다`() =
            runBlocking<Unit> {
                stubBase()
                stubNow(wms = 92, inventory = 100)
                stubApply()

                val result: ReconciliationProposalResult = service.approve(command())

                assertEquals(AdjustmentStatus.APPLIED, result.status)
                assertEquals(reviewer, result.approvedBy)
                assertEquals(now, result.approvedAt)
                assertEquals(70L, result.inventoryId)
                assertEquals(-8, result.quantityChange)
                assertEquals(false, result.requiresAdmin)
                val saved = argumentCaptor<StockAdjustment>()
                verifyBlocking(stockAdjustmentRepository) { updateState(saved.capture(), eq(AdjustmentStatus.PENDING)) }
                assertEquals(AdjustmentStatus.APPLIED, saved.firstValue.status)
                val adjust = argumentCaptor<AdjustInventoryCommand>()
                verifyBlocking(adjustInventoryUseCase) { adjust(adjust.capture()) }
                assertEquals(AdjustmentKind.RECONCILIATION, adjust.firstValue.kind)
                assertEquals(key, adjust.firstValue.idempotencyKey)
                assertEquals(reviewer, adjust.firstValue.requesterService)
                assertEquals(10L, adjust.firstValue.warehouseId)
                assertEquals(AdjustInventoryCommand.Item(100L, 7L, QualityStatus.NORMAL, -8, 11L), adjust.firstValue.items.single())
                verifyBlocking(stockAdjustmentRepository) { assignInventoryIds(mapOf(11L to 70L)) }
            }

        @Test
        fun `변동량이 임계치를 넘으면 창고 관리자는 거부되고 WMS를 조회하지 않는다`() =
            runBlocking<Unit> {
                stubBase(threshold = 7)

                assertThrows<ReconciliationAdminRequiredException> { service.approve(command(isAdmin = false)) }

                verifyBlocking(wmsStockPort, never()) { findQuantities(any(), any(), any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `변동량이 임계치와 같으면 창고 관리자도 승인하고 임계치를 넘어도 본사 관리자는 승인한다`() =
            runBlocking<Unit> {
                stubBase(threshold = 8)
                stubNow(wms = 92, inventory = 100)
                stubApply()
                assertEquals(AdjustmentStatus.APPLIED, service.approve(command(isAdmin = false)).status)
            }

        @Test
        fun `임계치를 넘어도 본사 관리자는 승인한다`() =
            runBlocking<Unit> {
                stubBase(threshold = 7)
                stubNow(wms = 92, inventory = 100)
                stubApply()

                val result: ReconciliationProposalResult = service.approve(command(isAdmin = true))

                assertEquals(true, result.requiresAdmin)
                assertEquals(AdjustmentStatus.APPLIED, result.status)
            }

        @Test
        fun `WMS 수량이 바뀌었으면 보정안만 새 값으로 고치고 반영하지 않은 채 변동 오류다`() =
            runBlocking<Unit> {
                val adjustment: StockAdjustment = proposal()
                stubBase(listOf(adjustment))
                stubNow(wms = 95, inventory = 100)
                whenever(stockAdjustmentRepository.updateReconciliationItem(any())).thenReturn(true)

                assertThrows<ReconciliationChangedException> { service.approve(command()) }

                assertEquals(-5, adjustment.items.single().quantityChange)
                assertEquals(95, adjustment.items.single().wmsQuantity)
                assertEquals(AdjustmentStatus.PENDING, adjustment.status)
                verifyBlocking(stockAdjustmentRepository) { updateReconciliationItem(adjustment.items.single()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
                verifyBlocking(stockAdjustmentRepository, never()) { updateState(any(), any()) }
            }

        @Test
        fun `inventory 수량만 바뀌어도 새 값으로 고치고 변동 오류다`() =
            runBlocking<Unit> {
                val adjustment: StockAdjustment = proposal()
                stubBase(listOf(adjustment))
                stubNow(wms = 92, inventory = 98)
                whenever(stockAdjustmentRepository.updateReconciliationItem(any())).thenReturn(true)

                assertThrows<ReconciliationChangedException> { service.approve(command()) }

                assertEquals(98, adjustment.items.single().inventoryQuantity)
                assertEquals(-6, adjustment.items.single().quantityChange)
            }

        @Test
        fun `불일치가 사라졌으면 보정안을 거부 상태로 닫고 변동 오류다`() =
            runBlocking<Unit> {
                val adjustment: StockAdjustment = proposal()
                stubBase(listOf(adjustment))
                stubNow(wms = 100, inventory = 100)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(true)

                assertThrows<ReconciliationChangedException> { service.approve(command()) }

                assertEquals(AdjustmentStatus.REJECTED, adjustment.status)
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `inventory 행이 없으면 수량 0으로 보고 WMS에 항목이 없으면 0으로 본다`() =
            runBlocking<Unit> {
                val adjustment: StockAdjustment = proposal(inventoryQuantity = 0, wmsQuantity = 5)
                stubBase(listOf(adjustment))
                whenever(
                    wmsStockPort.findQuantities(10L, 100L, "LOT-A"),
                ).thenReturn(listOf(WmsStockQuantity(100L, "LOT-A", QualityStatus.DEFECTIVE, 9)))
                whenever(getWarehouseQuantitiesUseCase.getQuantities(10L, 100L, "LOT-A")).thenReturn(emptyList())
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(true)

                // 다른 품질 상태의 WMS 수량은 무시하므로 WMS 0, inventory 0이 되어 불일치가 사라진다
                assertThrows<ReconciliationChangedException> { service.approve(command()) }

                assertEquals(AdjustmentStatus.REJECTED, adjustment.status)
            }

        @Test
        fun `숫자가 달라진 이유가 다른 요청이 먼저 반영해서이면 고치지 않고 다시 읽어 현재 상태를 돌려준다`() =
            runBlocking<Unit> {
                // 처음 읽을 때는 처리 대기였는데, 다른 요청이 반영한 뒤라 inventory 수량이 92로 바뀌어 보인다
                stubBase(listOf(proposal(), proposal(AdjustmentStatus.APPLIED)))
                stubNow(wms = 92, inventory = 92)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(false)

                val result: ReconciliationProposalResult = service.approve(command())

                assertEquals(AdjustmentStatus.APPLIED, result.status)
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `숫자만 달라졌고 그 사이 상태가 바뀌어 항목 갱신이 밀리면 다시 읽어 판정한다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(), proposal(AdjustmentStatus.APPLIED)))
                stubNow(wms = 95, inventory = 100)
                whenever(stockAdjustmentRepository.updateReconciliationItem(any())).thenReturn(false)

                assertEquals(AdjustmentStatus.APPLIED, service.approve(command()).status)
                verifyBlocking(stockAdjustmentRepository, times(2)) { findById(1L) }
            }

        @Test
        fun `이미 반영된 보정안은 WMS를 조회하지 않고 현재 상태를 돌려준다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(AdjustmentStatus.APPLIED)))

                val result: ReconciliationProposalResult = service.approve(command())

                assertEquals(AdjustmentStatus.APPLIED, result.status)
                assertEquals("someone", result.approvedBy)
                verifyBlocking(wmsStockPort, never()) { findQuantities(any(), any(), any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `이미 반영된 보정안은 임계치를 넘어도 창고 관리자에게 현재 상태를 돌려준다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(AdjustmentStatus.APPLIED)), threshold = 1)

                assertEquals(AdjustmentStatus.APPLIED, service.approve(command(isAdmin = false)).status)
            }

        @Test
        fun `거부된 보정안은 승인할 수 없다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(AdjustmentStatus.REJECTED)))

                assertThrows<InvalidAdjustmentStateException> { service.approve(command()) }
            }

        @Test
        fun `없는 보정안과 실사 조정은 없는 것으로 본다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.findById(1L)).thenReturn(null, proposal(AdjustmentStatus.APPLIED, AdjustmentType.AUDIT))

                assertThrows<AdjustmentNotFoundException> { service.approve(command()) }
                assertThrows<AdjustmentNotFoundException> { service.approve(command()) }
            }

        @Test
        fun `창고가 범위 밖이면 거부하고 아무것도 조회하지 않는다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.findById(1L)).thenReturn(proposal())

                assertThrows<WarehouseAccessDeniedException> { service.approve(command(scope = WarehouseScope.Only(setOf(20L)))) }

                verifyBlocking(getLotUseCase, never()) { getByIds(any()) }
            }

        @Test
        fun `다른 요청이 먼저 반영해 조건부 갱신에서 밀리면 다시 읽어 현재 상태를 돌려준다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(), proposal(AdjustmentStatus.APPLIED)))
                stubNow(wms = 92, inventory = 100)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(false)

                val result: ReconciliationProposalResult = service.approve(command())

                assertEquals(AdjustmentStatus.APPLIED, result.status)
                assertEquals("someone", result.approvedBy)
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `밀린 사이 거부되었으면 다시 읽은 뒤 상태 오류다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(), proposal(AdjustmentStatus.REJECTED)))
                stubNow(wms = 92, inventory = 100)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(false)

                assertThrows<InvalidAdjustmentStateException> { service.approve(command()) }
            }

        @Test
        fun `재고 반영이 실패하면 예외를 그대로 던지고 항목의 재고 행을 기록하지 않는다`() =
            runBlocking<Unit> {
                stubBase()
                stubNow(wms = 92, inventory = 100)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(true)
                whenever(adjustInventoryUseCase.adjust(any())).thenThrow(InsufficientAvailableQuantityException())

                assertThrows<InsufficientAvailableQuantityException> { service.approve(command()) }

                verifyBlocking(stockAdjustmentRepository, never()) { assignInventoryIds(any()) }
            }

        @Test
        fun `WMS 조회가 실패하면 그대로 던지고 아무것도 바꾸지 않는다`() =
            runBlocking<Unit> {
                stubBase()
                whenever(wmsStockPort.findQuantities(10L, 100L, "LOT-A")).thenThrow(WmsUnavailableException())

                assertThrows<WmsUnavailableException> { service.approve(command()) }

                verifyBlocking(stockAdjustmentRepository, never()) { updateState(any(), any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }
    }

    @Nested
    inner class `거부` {
        @Test
        fun `처리 대기 보정안을 거부 상태로 바꾼다`() =
            runBlocking<Unit> {
                stubBase(threshold = 1)
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(true)

                val result: ReconciliationProposalResult = service.reject(command(isAdmin = false))

                assertEquals(AdjustmentStatus.REJECTED, result.status)
                assertEquals(true, result.requiresAdmin)
                verifyBlocking(wmsStockPort, never()) { findQuantities(any(), any(), any()) }
                verifyBlocking(adjustInventoryUseCase, never()) { adjust(any()) }
            }

        @Test
        fun `이미 거부된 보정안은 바꾸지 않고 현재 상태를 돌려주고 반영된 보정안은 상태 오류다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(AdjustmentStatus.REJECTED), proposal(AdjustmentStatus.APPLIED)))

                assertEquals(AdjustmentStatus.REJECTED, service.reject(command()).status)
                assertThrows<InvalidAdjustmentStateException> { service.reject(command()) }
                verifyBlocking(stockAdjustmentRepository, never()) { updateState(any(), any()) }
            }

        @Test
        fun `조건부 갱신에서 밀리면 다시 읽어 판정한다`() =
            runBlocking<Unit> {
                stubBase(listOf(proposal(), proposal(AdjustmentStatus.REJECTED)))
                whenever(stockAdjustmentRepository.updateState(any(), eq(AdjustmentStatus.PENDING))).thenReturn(false)

                assertEquals(AdjustmentStatus.REJECTED, service.reject(command()).status)
                verifyBlocking(stockAdjustmentRepository, times(2)) { findById(1L) }
            }

        @Test
        fun `없는 보정안이거나 창고가 범위 밖이면 거부한다`() =
            runBlocking<Unit> {
                whenever(stockAdjustmentRepository.findById(1L)).thenReturn(null, proposal())

                assertThrows<AdjustmentNotFoundException> { service.reject(command()) }
                assertThrows<WarehouseAccessDeniedException> { service.reject(command(scope = WarehouseScope.Only(setOf(20L)))) }
            }
    }
}
