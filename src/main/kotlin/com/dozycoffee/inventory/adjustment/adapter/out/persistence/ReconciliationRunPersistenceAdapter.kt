package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.application.port.out.ReconciliationRunRepository
import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/** 끝난 실행의 갱신은 `DatabaseClient`로 직접 쓰므로 Auditing이 동작하지 않아 `updated_at`, `updated_by`를 SQL에 직접 넣는다 */
@Component
class ReconciliationRunPersistenceAdapter(
    private val reconciliationRunR2dbcRepository: ReconciliationRunR2dbcRepository,
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) : ReconciliationRunRepository {
    override suspend fun save(run: ReconciliationRun): ReconciliationRun {
        check(run.reconciliationRunId == null) { "대사 실행은 새로 저장만 할 수 있다. 끝난 실행은 update로 저장한다" }
        return reconciliationRunR2dbcRepository.save(ReconciliationRunEntity.from(run)).toDomain()
    }

    override suspend fun findById(reconciliationRunId: Long): ReconciliationRun? =
        reconciliationRunR2dbcRepository.findById(reconciliationRunId)?.toDomain()

    override suspend fun update(run: ReconciliationRun) {
        val runId: Long = checkNotNull(run.reconciliationRunId) { "저장되지 않은 대사 실행은 갱신할 수 없다" }
        val finishedAt: LocalDateTime? = run.finishedAt
        var spec: DatabaseClient.GenericExecuteSpec =
            databaseClient
                .sql(UPDATE)
                .bind("status", run.status.name)
                .bind("checkedCount", run.checkedCount)
                .bind("mismatchCount", run.mismatchCount)
                .bind("now", LocalDateTime.now(clock))
                .bind("actor", currentActorProvider.get().auditName)
                .bind("runId", runId)
        spec = if (finishedAt == null) spec.bindNull("finishedAt", LocalDateTime::class.java) else spec.bind("finishedAt", finishedAt)
        spec.fetch().awaitRowsUpdated()
    }

    private companion object {
        const val UPDATE: String =
            """
            UPDATE reconciliation_run
               SET status = :status, finished_at = :finishedAt, checked_count = :checkedCount, mismatch_count = :mismatchCount,
                   updated_at = :now, updated_by = :actor
             WHERE reconciliation_run_id = :runId
            """
    }
}
