package com.dozycoffee.ims.inventory.domain.model

import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.inventory.domain.enumeration.HistoryType
import com.dozycoffee.ims.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.ims.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.ims.inventory.domain.valueobject.IdempotencyKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class InventoryHistoryModelTest {
    private val key: IdempotencyKey = IdempotencyKey.of("svc-wms-inbound-1")

    private fun create(
        type: HistoryType = HistoryType.INBOUND,
        change: Int = 5,
        after: Int = 15,
        requester: String? = "svc-wms",
    ): InventoryHistory = InventoryHistory.create(1L, type, change, after, ReferenceType.INBOUND_ITEM, 77L, key, requester)

    private fun assertError(
        code: InventoryErrorCode,
        block: () -> Unit,
    ) {
        val e: InvalidDomainValueException = assertThrows { block() }
        assertEquals(code, e.errorCode)
    }

    @Test
    fun `이력을 만들면 식별자와 생성 시각이 없고 입력 값을 그대로 가진다`() {
        val history: InventoryHistory = create()

        assertNull(history.inventoryHistoryId)
        assertNull(history.createdAt)
        assertEquals(5, history.quantityChange)
        assertEquals(15, history.quantityAfter)
        assertEquals(key, history.idempotencyKey)
        assertEquals("svc-wms", history.requesterService)
    }

    @Test
    fun `변동량은 0일 수 없다`() {
        HistoryType.entries.forEach { type: HistoryType ->
            assertError(InventoryErrorCode.INVALID_HISTORY_CHANGE) { create(type = type, change = 0) }
        }
    }

    @Test
    fun `입고와 반품은 증가, 출고와 폐기는 감소여야 한다`() {
        assertError(InventoryErrorCode.INVALID_HISTORY_CHANGE) { create(type = HistoryType.INBOUND, change = -1) }
        assertError(InventoryErrorCode.INVALID_HISTORY_CHANGE) { create(type = HistoryType.RETURN, change = -1) }
        assertError(InventoryErrorCode.INVALID_HISTORY_CHANGE) { create(type = HistoryType.OUTBOUND, change = 1) }
        assertError(InventoryErrorCode.INVALID_HISTORY_CHANGE) { create(type = HistoryType.DISPOSAL, change = 1) }
        create(type = HistoryType.RETURN, change = 1)
        create(type = HistoryType.OUTBOUND, change = -1, after = 0)
        create(type = HistoryType.DISPOSAL, change = -1, after = 0)
    }

    @Test
    fun `조정과 대사와 품질 전환은 증가와 감소를 모두 기록할 수 있다`() {
        listOf(HistoryType.ADJUSTMENT, HistoryType.RECONCILIATION, HistoryType.QUALITY_TRANSFER).forEach { type: HistoryType ->
            create(type = type, change = 3)
            create(type = type, change = -3, after = 0)
        }
    }

    @Test
    fun `변경 후 수량은 0 이상이어야 한다`() {
        create(type = HistoryType.OUTBOUND, change = -5, after = 0)
        assertError(InventoryErrorCode.INVALID_HISTORY_AFTER) { create(type = HistoryType.OUTBOUND, change = -5, after = -1) }
    }

    @Test
    fun `요청 서비스는 비어 있을 수 없고 50자를 넘을 수 없다`() {
        listOf(null, "", "  ", "a".repeat(51)).forEach { requester: String? ->
            assertError(InventoryErrorCode.INVALID_REQUESTER_SERVICE) { create(requester = requester) }
        }
        create(requester = "a".repeat(50))
        create(requester = "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
    }

    @Test
    fun `복원한 이력은 생성 시각을 가지고 식별자로 동등성을 판단한다`() {
        val at: LocalDateTime = LocalDateTime.of(2026, 10, 7, 9, 0)
        val a: InventoryHistory =
            InventoryHistory.reconstitute(1L, 1L, HistoryType.INBOUND, 5, 15, ReferenceType.INBOUND_ITEM, 77L, key, "svc-wms", at)
        val b: InventoryHistory =
            InventoryHistory.reconstitute(1L, 2L, HistoryType.RETURN, 1, 1, ReferenceType.RETURN_ITEM, 1L, key, "svc-oms", at)
        val c: InventoryHistory =
            InventoryHistory.reconstitute(2L, 1L, HistoryType.INBOUND, 5, 15, ReferenceType.INBOUND_ITEM, 77L, key, "svc-wms", at)

        assertEquals(at, a.createdAt)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
