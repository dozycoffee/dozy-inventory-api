package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun
import com.dozycoffee.inventory.global.common.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("reconciliation_run")
class ReconciliationRunEntity(
    @Id
    @Column("reconciliation_run_id")
    var reconciliationRunId: Long? = null,
    var warehouseId: Long,
    var status: ReconciliationStatus,
    var startedAt: LocalDateTime,
    var finishedAt: LocalDateTime?,
    var checkedCount: Int,
    var mismatchCount: Int,
) : BaseEntity() {
    fun toDomain(): ReconciliationRun =
        ReconciliationRun.reconstitute(
            reconciliationRunId = checkNotNull(reconciliationRunId) { "저장된 대사 실행은 식별자가 있어야 한다" },
            warehouseId = warehouseId,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            checkedCount = checkedCount,
            mismatchCount = mismatchCount,
        )

    companion object {
        fun from(run: ReconciliationRun): ReconciliationRunEntity =
            ReconciliationRunEntity(
                reconciliationRunId = run.reconciliationRunId,
                warehouseId = run.warehouseId,
                status = run.status,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                checkedCount = run.checkedCount,
                mismatchCount = run.mismatchCount,
            )
    }
}
