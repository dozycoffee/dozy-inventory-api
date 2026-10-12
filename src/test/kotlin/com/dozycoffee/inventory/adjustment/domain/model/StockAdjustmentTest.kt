package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationRevision
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
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

    @Nested
    inner class `대사 보정안` {
        private fun proposal(
            inventoryQuantity: Int = 100,
            wmsQuantity: Int = 92,
        ): StockAdjustment =
            StockAdjustment.reconciliation(
                10L,
                5L,
                StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, inventoryQuantity, wmsQuantity),
                RequesterService.of("system"),
                IdempotencyKey.of("reconciliation-10-20261012-1-NORMAL"),
            )

        private fun restored(
            status: AdjustmentStatus,
            type: AdjustmentType = AdjustmentType.RECONCILIATION,
        ): StockAdjustment =
            StockAdjustment.reconstitute(
                1L,
                10L,
                type,
                status,
                null,
                RequesterService.of("system"),
                if (status == AdjustmentStatus.APPLIED) "manager-1" else null,
                if (status == AdjustmentStatus.APPLIED) now else null,
                IdempotencyKey.of("reconciliation-10-20261012-1-NORMAL"),
                listOf(StockAdjustmentItem.reconstitute(1L, null, 1L, QualityStatus.NORMAL, -8, 100, 92)),
                5L,
            )

        @Test
        fun `불일치한 행으로 처리 대기 보정안을 만들고 변동량은 WMS 수량에서 inventory 수량을 뺀 값이다`() {
            val adjustment: StockAdjustment = proposal(100, 92)

            assertEquals(AdjustmentType.RECONCILIATION, adjustment.adjustmentType)
            assertEquals(AdjustmentStatus.PENDING, adjustment.status)
            assertEquals(5L, adjustment.reconciliationRunId)
            assertEquals(RequesterService.of("system"), adjustment.requestedBy)
            assertNull(adjustment.externalReferenceId)
            assertNull(adjustment.approvedBy)
            val item: StockAdjustmentItem = adjustment.items.single()
            assertEquals(-8, item.quantityChange)
            assertEquals(100, item.inventoryQuantity)
            assertEquals(92, item.wmsQuantity)
            assertEquals(12, proposal(0, 12).items.single().quantityChange)
        }

        @Test
        fun `두 수량이 같거나 음수이거나 실행과 창고가 양수가 아니면 거부한다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) { StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, 5, 5) }
            assertInvalid(
                AdjustmentErrorCode.INVALID_RECONCILIATION,
            ) { StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, -1, 5) }
            assertInvalid(
                AdjustmentErrorCode.INVALID_RECONCILIATION,
            ) { StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, 5, -1) }
            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) { StockAdjustmentItem.reconciliation(0L, QualityStatus.NORMAL, 5, 6) }
            val ok: StockAdjustmentItem = StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, 5, 6)
            val key = IdempotencyKey.of("k")
            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) {
                StockAdjustment.reconciliation(0L, 5L, ok, RequesterService.of("system"), key)
            }
            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) {
                StockAdjustment.reconciliation(10L, 0L, ok, RequesterService.of("system"), key)
            }
        }

        @Test
        fun `처리 대기 보정안을 승인하면 반영 상태가 되고 승인자와 시각을 기록한다`() {
            val adjustment: StockAdjustment = restored(AdjustmentStatus.PENDING)

            assertEquals(true, adjustment.approve("manager-1", now))

            assertEquals(AdjustmentStatus.APPLIED, adjustment.status)
            assertEquals("manager-1", adjustment.approvedBy)
            assertEquals(now, adjustment.approvedAt)
        }

        @Test
        fun `이미 반영된 보정안의 승인은 바꾸지 않고 false이고 거부된 보정안의 승인은 상태 오류다`() {
            val applied: StockAdjustment = restored(AdjustmentStatus.APPLIED)

            assertEquals(false, applied.approve("other", now.plusDays(1)))
            assertEquals("manager-1", applied.approvedBy)
            assertThrows<InvalidAdjustmentStateException> { restored(AdjustmentStatus.REJECTED).approve("manager-1", now) }
            assertThrows<InvalidAdjustmentStateException> { restored(AdjustmentStatus.APPROVED).approve("manager-1", now) }
        }

        @Test
        fun `승인자는 비어 있을 수 없고 100자를 넘을 수 없다`() {
            assertInvalid(AdjustmentErrorCode.INVALID_APPROVER) { restored(AdjustmentStatus.PENDING).approve(" ", now) }
            assertInvalid(AdjustmentErrorCode.INVALID_APPROVER) { restored(AdjustmentStatus.PENDING).approve("a".repeat(101), now) }
        }

        @Test
        fun `처리 대기 보정안을 거부하고 이미 거부된 보정안은 false이며 반영된 보정안은 상태 오류다`() {
            val pending: StockAdjustment = restored(AdjustmentStatus.PENDING)

            assertEquals(true, pending.reject())
            assertEquals(AdjustmentStatus.REJECTED, pending.status)
            assertEquals(false, restored(AdjustmentStatus.REJECTED).reject())
            assertThrows<InvalidAdjustmentStateException> { restored(AdjustmentStatus.APPLIED).reject() }
        }

        @Test
        fun `실사 조정은 승인하거나 거부하거나 고칠 수 없다`() {
            val audit: StockAdjustment = restored(AdjustmentStatus.APPLIED, AdjustmentType.AUDIT)

            assertThrows<InvalidAdjustmentStateException> { audit.approve("manager-1", now) }
            assertThrows<InvalidAdjustmentStateException> { audit.reject() }
            assertThrows<InvalidAdjustmentStateException> { audit.revise(1, 2) }
        }

        @Test
        fun `다시 확인한 수량이 같으면 그대로이고 다르면 수량과 변동량을 새 값으로 바꾼다`() {
            val adjustment: StockAdjustment = proposal(100, 92)

            assertEquals(ReconciliationRevision.UNCHANGED, adjustment.revise(100, 92))
            assertEquals(-8, adjustment.items.single().quantityChange)

            assertEquals(ReconciliationRevision.UPDATED, adjustment.revise(100, 95))
            assertEquals(95, adjustment.items.single().wmsQuantity)
            assertEquals(-5, adjustment.items.single().quantityChange)
            assertEquals(ReconciliationRevision.UPDATED, adjustment.revise(90, 95))
            assertEquals(90, adjustment.items.single().inventoryQuantity)
            assertEquals(5, adjustment.items.single().quantityChange)
            assertEquals(AdjustmentStatus.PENDING, adjustment.status)
        }

        @Test
        fun `두 수량이 같아지면 불일치가 사라진 것이라 거부 상태로 닫고 수량은 그대로 둔다`() {
            val adjustment: StockAdjustment = proposal(100, 92)

            assertEquals(ReconciliationRevision.RESOLVED, adjustment.revise(92, 92))

            assertEquals(AdjustmentStatus.REJECTED, adjustment.status)
            assertEquals(-8, adjustment.items.single().quantityChange)
        }

        @Test
        fun `처리 대기가 아닌 보정안은 고칠 수 없다`() {
            assertThrows<InvalidAdjustmentStateException> { restored(AdjustmentStatus.APPLIED).revise(1, 2) }
            assertThrows<InvalidAdjustmentStateException> { restored(AdjustmentStatus.REJECTED).revise(1, 2) }
        }

        @Test
        fun `항목을 고칠 때 두 수량이 같거나 음수이면 거부한다`() {
            val item: StockAdjustmentItem = StockAdjustmentItem.reconciliation(1L, QualityStatus.NORMAL, 10, 4)

            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) { item.revise(5, 5) }
            assertInvalid(AdjustmentErrorCode.INVALID_RECONCILIATION) { item.revise(-1, 5) }
            assertEquals(-6, item.quantityChange)
        }
    }
}
