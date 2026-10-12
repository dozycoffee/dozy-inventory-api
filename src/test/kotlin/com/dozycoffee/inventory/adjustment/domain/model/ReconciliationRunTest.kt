package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class ReconciliationRunTest {
    private val start: LocalDateTime = LocalDateTime.of(2026, 10, 12, 1, 30)
    private val end: LocalDateTime = start.plusMinutes(2)

    private fun assertInvalid(block: () -> Unit) {
        val e: InvalidDomainValueException = assertThrows(block)
        assertEquals(AdjustmentErrorCode.INVALID_RECONCILIATION.code, e.errorCode.code)
    }

    @Test
    fun `창고에 대해 실행 중 상태로 시작하고 수는 0이다`() {
        val run: ReconciliationRun = ReconciliationRun.start(10L, start)

        assertEquals(ReconciliationStatus.RUNNING, run.status)
        assertEquals(10L, run.warehouseId)
        assertEquals(start, run.startedAt)
        assertNull(run.finishedAt)
        assertNull(run.reconciliationRunId)
        assertEquals(0, run.checkedCount)
        assertEquals(0, run.mismatchCount)
    }

    @Test
    fun `창고 ID는 양수여야 한다`() {
        assertInvalid { ReconciliationRun.start(0L, start) }
    }

    @Test
    fun `비교한 항목 수와 불일치 수를 기록하고 완료한다`() {
        val run: ReconciliationRun = ReconciliationRun.start(10L, start)

        run.complete(120, 3, end)

        assertEquals(ReconciliationStatus.COMPLETED, run.status)
        assertEquals(120, run.checkedCount)
        assertEquals(3, run.mismatchCount)
        assertEquals(end, run.finishedAt)
    }

    @Test
    fun `불일치 수는 비교한 항목 수를 넘을 수 없고 음수일 수 없다`() {
        assertInvalid { ReconciliationRun.start(10L, start).complete(2, 3, end) }
        assertInvalid { ReconciliationRun.start(10L, start).complete(-1, 0, end) }
        assertInvalid { ReconciliationRun.start(10L, start).complete(1, -1, end) }
        ReconciliationRun.start(10L, start).complete(3, 3, end)
    }

    @Test
    fun `실패로 끝낼 수 있고 끝난 실행은 다시 끝낼 수 없다`() {
        val failed: ReconciliationRun = ReconciliationRun.start(10L, start)
        failed.fail(end)
        assertEquals(ReconciliationStatus.FAILED, failed.status)
        assertEquals(end, failed.finishedAt)

        assertInvalid { failed.fail(end) }
        assertInvalid { failed.complete(1, 0, end) }
        val done: ReconciliationRun = ReconciliationRun.start(10L, start)
        done.complete(1, 0, end)
        assertInvalid { done.fail(end) }
        assertInvalid { done.complete(1, 0, end) }
    }

    @Test
    fun `저장된 뒤에는 식별자가 같으면 같다`() {
        fun restored(id: Long): ReconciliationRun = ReconciliationRun.reconstitute(id, 10L, ReconciliationStatus.RUNNING, start, null, 0, 0)

        assertEquals(restored(1L), restored(1L))
        assertEquals(false, restored(1L) == restored(2L))
        val a: ReconciliationRun = ReconciliationRun.start(10L, start)
        assertEquals(a, a)
        assertEquals(false, a == ReconciliationRun.start(10L, start))
    }
}
