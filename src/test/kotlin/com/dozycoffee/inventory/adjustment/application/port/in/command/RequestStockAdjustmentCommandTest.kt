package com.dozycoffee.inventory.adjustment.application.port.`in`.command

import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RequestStockAdjustmentCommandTest {
    private fun item(
        productId: Long = 100L,
        lotNumber: String = "LOT-A",
        quality: QualityStatus = QualityStatus.NORMAL,
        change: Int = 5,
    ): RequestStockAdjustmentCommand.Item = RequestStockAdjustmentCommand.Item(productId, lotNumber, quality, change)

    private fun command(
        vararg items: RequestStockAdjustmentCommand.Item,
        warehouseId: Long = 10L,
        externalReferenceId: Long = 77L,
    ): RequestStockAdjustmentCommand =
        RequestStockAdjustmentCommand(warehouseId, externalReferenceId, items.toList(), null, "wms-audit-1", "svc-wms")

    private fun assertInvalid(block: () -> Unit) {
        val e: InvalidDomainValueException = assertThrows(block)
        assertEquals(AdjustmentErrorCode.INVALID_STOCK_ADJUSTMENT.code, e.errorCode.code)
    }

    @Nested
    inner class `유효한 요청` {
        @Test
        fun `같은 Lot 번호도 상품이나 품질 상태가 다르면 별도 항목이다`() {
            command(
                item(100L, "LOT-A", QualityStatus.NORMAL),
                item(101L, "LOT-A", QualityStatus.NORMAL),
                item(100L, "LOT-A", QualityStatus.DEFECTIVE),
            )
        }

        @Test
        fun `항목은 500개까지 담을 수 있다`() {
            command(*(1..500).map { item(lotNumber = "L-$it") }.toTypedArray())
        }
    }

    @Nested
    inner class `잘못된 요청` {
        @Test
        fun `창고나 실사 건 ID가 양수가 아니면 거부한다`() {
            assertInvalid { command(item(), warehouseId = 0L) }
            assertInvalid { command(item(), externalReferenceId = 0L) }
        }

        @Test
        fun `항목이 없거나 500개를 넘으면 거부한다`() {
            assertInvalid { command() }
            assertInvalid { command(*(1..501).map { item(lotNumber = "L-$it") }.toTypedArray()) }
        }

        @Test
        fun `변동량이 0이거나 상품 ID가 양수가 아니면 거부한다`() {
            assertInvalid { command(item(change = 0)) }
            assertInvalid { command(item(productId = 0L)) }
        }

        @Test
        fun `Lot 번호가 비어 있거나 50자를 넘으면 거부한다`() {
            assertInvalid { command(item(lotNumber = " ")) }
            assertInvalid { command(item(lotNumber = "L".repeat(51))) }
            command(item(lotNumber = "L".repeat(50)))
        }

        @Test
        fun `같은 상품, Lot 번호, 품질 상태가 중복되면 거부한다`() {
            assertInvalid { command(item(change = 3), item(change = -3)) }
        }
    }
}
