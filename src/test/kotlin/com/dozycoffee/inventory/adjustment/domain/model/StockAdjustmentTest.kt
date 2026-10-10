package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class StockAdjustmentTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 10, 12, 0)

    private fun item(
        lotId: Long = 1L,
        quality: QualityStatus = QualityStatus.NORMAL,
        change: Int = 5,
    ): StockAdjustmentItem = StockAdjustmentItem.create(lotId, quality, change)

    private fun audit(
        items: List<StockAdjustmentItem> = listOf(item()),
        warehouseId: Long = 10L,
        externalReferenceId: Long = 77L,
        approvedBy: String? = null,
    ): StockAdjustment =
        StockAdjustment.audit(
            warehouseId,
            externalReferenceId,
            items,
            RequesterService.of("svc-wms"),
            approvedBy,
            IdempotencyKey.of("wms-audit-1"),
            now,
        )

    private fun assertInvalid(
        code: AdjustmentErrorCode,
        block: () -> Unit,
    ) {
        val e: InvalidDomainValueException = assertThrows(block)
        assertEquals(code.code, e.errorCode.code)
    }

    @Nested
    inner class `실사 조정 생성` {
        @Test
        fun `요청 즉시 반영된 상태로 만들고 아직 식별자는 없다`() {
            val adjustment: StockAdjustment = audit()

            assertEquals(AdjustmentType.AUDIT, adjustment.adjustmentType)
            assertEquals(AdjustmentStatus.APPLIED, adjustment.status)
            assertEquals(77L, adjustment.externalReferenceId)
            assertEquals(10L, adjustment.warehouseId)
            assertNull(adjustment.stockAdjustmentId)
        }

        @Test
        fun `승인자가 없으면 승인 정보를 남기지 않는다`() {
            val adjustment: StockAdjustment = audit(approvedBy = null)

            assertNull(adjustment.approvedBy)
            assertNull(adjustment.approvedAt)
        }

        @Test
        fun `승인자가 있으면 승인 시각을 지금으로 기록한다`() {
            val adjustment: StockAdjustment = audit(approvedBy = "manager-7")

            assertEquals("manager-7", adjustment.approvedBy)
            assertEquals(now, adjustment.approvedAt)
        }

        @Test
        fun `항목은 증가와 감소를 함께 담고 같은 Lot이어도 품질 상태가 다르면 허용한다`() {
            val adjustment: StockAdjustment =
                audit(
                    listOf(
                        item(1L, QualityStatus.NORMAL, 5),
                        item(1L, QualityStatus.DEFECTIVE, -2),
                        item(2L, QualityStatus.NORMAL, -1),
                    ),
                )

            assertEquals(3, adjustment.items.size)
        }
    }

    @Nested
    inner class `잘못된 조정` {
        @Test
        fun `창고나 실사 건 ID가 양수가 아니면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { audit(warehouseId = 0L) }
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { audit(externalReferenceId = 0L) }
        }

        @Test
        fun `항목이 없거나 500개를 넘으면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { audit(emptyList()) }
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { audit((1L..501L).map { item(lotId = it) }) }
        }

        @Test
        fun `항목은 500개까지 담을 수 있다`() {
            assertEquals(500, audit((1L..500L).map { item(lotId = it) }).items.size)
        }

        @Test
        fun `같은 Lot과 품질 상태가 두 번 나오면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { audit(listOf(item(1L, change = 3), item(1L, change = -3))) }
        }

        @Test
        fun `승인자가 비어 있거나 100자를 넘으면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_APPROVER) { audit(approvedBy = " ") }
            assertInvalid(AdjustmentErrorCode.INVALID_APPROVER) { audit(approvedBy = "a".repeat(101)) }
            assertEquals("a".repeat(100), audit(approvedBy = "a".repeat(100)).approvedBy)
        }
    }

    @Nested
    inner class `조정 항목` {
        @Test
        fun `변동량이 0이거나 Lot ID가 양수가 아니면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { item(change = 0) }
            assertInvalid(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT) { item(lotId = 0L) }
        }

        @Test
        fun `새 항목은 식별자와 대상 재고 행이 아직 없다`() {
            val item: StockAdjustmentItem = item(change = -4)

            assertNull(item.stockAdjustmentItemId)
            assertNull(item.inventoryId)
            assertEquals(-4, item.quantityChange)
        }

        @Test
        fun `식별자가 같은 항목은 같다`() {
            val a = StockAdjustmentItem.reconstitute(1L, 5L, 1L, QualityStatus.NORMAL, 3)
            val b = StockAdjustmentItem.reconstitute(1L, null, 2L, QualityStatus.DEFECTIVE, -1)

            assertEquals(a, b)
        }
    }
}
