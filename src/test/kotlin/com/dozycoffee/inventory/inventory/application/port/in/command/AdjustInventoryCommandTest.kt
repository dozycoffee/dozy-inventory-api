package com.dozycoffee.inventory.inventory.application.port.`in`.command

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AdjustInventoryCommandTest {
    private fun item(
        lotId: Long = 1L,
        quality: QualityStatus = QualityStatus.NORMAL,
        change: Int = 5,
        referenceId: Long = 1L,
        productId: Long = 100L,
    ): AdjustInventoryCommand.Item = AdjustInventoryCommand.Item(productId, lotId, quality, change, referenceId)

    private fun command(
        vararg items: AdjustInventoryCommand.Item,
        warehouseId: Long = 10L,
    ): AdjustInventoryCommand = AdjustInventoryCommand(warehouseId, items.toList(), "wms-audit-1", "svc-wms")

    private fun assertInvalid(block: () -> Unit) {
        val e: InvalidDomainValueException = assertThrows(block)
        assertEquals(InventoryErrorCode.INVALID_ADJUST_REQUEST.code, e.errorCode.code)
    }

    @Nested
    inner class `유효한 요청` {
        @Test
        fun `증가와 감소 항목을 함께 담을 수 있다`() {
            val command = command(item(lotId = 1L, change = 5, referenceId = 1L), item(lotId = 2L, change = -3, referenceId = 2L))

            assertEquals(2, command.items.size)
        }

        @Test
        fun `같은 Lot이어도 품질 상태가 다르면 별도 항목이다`() {
            command(
                item(quality = QualityStatus.NORMAL, referenceId = 1L),
                item(quality = QualityStatus.DEFECTIVE, referenceId = 2L),
            )
        }

        @Test
        fun `항목은 500개까지 담을 수 있다`() {
            command(*(1L..500L).map { item(lotId = it, referenceId = it) }.toTypedArray())
        }
    }

    @Nested
    inner class `잘못된 요청` {
        @Test
        fun `항목이 없거나 500개를 넘으면 거부한다`() {
            assertInvalid { command() }
            assertInvalid { command(*(1L..501L).map { item(lotId = it, referenceId = it) }.toTypedArray()) }
        }

        @Test
        fun `변동량이 0이면 거부한다`() {
            assertInvalid { command(item(change = 0)) }
        }

        @Test
        fun `ID가 양수가 아니면 거부한다`() {
            assertInvalid { command(item(), warehouseId = 0L) }
            assertInvalid { command(item(productId = 0L)) }
            assertInvalid { command(item(lotId = 0L)) }
            assertInvalid { command(item(referenceId = 0L)) }
        }

        @Test
        fun `같은 Lot과 품질 상태가 중복되면 거부한다`() {
            assertInvalid { command(item(lotId = 1L, referenceId = 1L), item(lotId = 1L, referenceId = 2L)) }
        }

        @Test
        fun `같은 원인 문서가 중복되면 거부한다`() {
            assertInvalid { command(item(lotId = 1L, referenceId = 1L), item(lotId = 2L, referenceId = 1L)) }
        }
    }
}
