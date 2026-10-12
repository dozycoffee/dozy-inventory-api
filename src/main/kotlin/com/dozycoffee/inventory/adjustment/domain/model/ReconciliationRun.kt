package com.dozycoffee.inventory.adjustment.domain.model

import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import java.time.LocalDateTime

/**
 * 창고 하나의 대사 실행 기록(ADR-0033). `RUNNING`으로 시작해 비교한 항목 수와 불일치 수와 함께 `COMPLETED`로 끝나거나 `FAILED`로 끝난다.
 * 끝난 실행은 다시 바꿀 수 없다.
 */
class ReconciliationRun private constructor(
    val reconciliationRunId: Long?,
    val warehouseId: Long,
    status: ReconciliationStatus,
    val startedAt: LocalDateTime,
    finishedAt: LocalDateTime?,
    checkedCount: Int,
    mismatchCount: Int,
) {
    var status: ReconciliationStatus = status
        private set

    var finishedAt: LocalDateTime? = finishedAt
        private set

    var checkedCount: Int = checkedCount
        private set

    var mismatchCount: Int = mismatchCount
        private set

    /** 비교한 항목 수 [checkedCount]와 불일치 수 [mismatchCount]를 기록하고 끝낸다. 불일치는 비교한 항목보다 많을 수 없다 */
    fun complete(
        checkedCount: Int,
        mismatchCount: Int,
        now: LocalDateTime,
    ) {
        if (status != ReconciliationStatus.RUNNING || checkedCount < 0 || mismatchCount < 0 || mismatchCount > checkedCount) {
            throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
        }
        this.status = ReconciliationStatus.COMPLETED
        this.checkedCount = checkedCount
        this.mismatchCount = mismatchCount
        this.finishedAt = now
    }

    fun fail(now: LocalDateTime) {
        if (status != ReconciliationStatus.RUNNING) throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
        this.status = ReconciliationStatus.FAILED
        this.finishedAt = now
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReconciliationRun) return false
        return reconciliationRunId != null && reconciliationRunId == other.reconciliationRunId
    }

    override fun hashCode(): Int = reconciliationRunId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun start(
            warehouseId: Long,
            now: LocalDateTime,
        ): ReconciliationRun {
            if (warehouseId < 1) throw InvalidDomainValueException(AdjustmentErrorCode.INVALID_RECONCILIATION)
            return ReconciliationRun(null, warehouseId, ReconciliationStatus.RUNNING, now, null, 0, 0)
        }

        fun reconstitute(
            reconciliationRunId: Long,
            warehouseId: Long,
            status: ReconciliationStatus,
            startedAt: LocalDateTime,
            finishedAt: LocalDateTime?,
            checkedCount: Int,
            mismatchCount: Int,
        ): ReconciliationRun =
            ReconciliationRun(reconciliationRunId, warehouseId, status, startedAt, finishedAt, checkedCount, mismatchCount)
    }
}
