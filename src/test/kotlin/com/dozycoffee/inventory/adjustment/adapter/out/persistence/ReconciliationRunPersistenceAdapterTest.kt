package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus
import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun
import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.security.LocalActorProvider
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

@DataR2dbcTest
@ActiveProfiles("local")
@Import(ReconciliationRunPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class ReconciliationRunPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: ReconciliationRunPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val start: LocalDateTime = LocalDateTime.of(2026, 10, 12, 1, 30)

    @BeforeEach
    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            databaseClient
                .sql("DELETE FROM reconciliation_run")
                .fetch()
                .rowsUpdated()
                .awaitFirst()
        }

    @Test
    fun `실행을 시작 상태로 저장하고 다시 읽는다`() =
        runBlocking<Unit> {
            val saved: ReconciliationRun = adapter.save(ReconciliationRun.start(10L, start))

            val loaded: ReconciliationRun = checkNotNull(adapter.findById(checkNotNull(saved.reconciliationRunId)))

            assertThat(loaded.status).isEqualTo(ReconciliationStatus.RUNNING)
            assertThat(loaded.warehouseId).isEqualTo(10L)
            assertThat(loaded.startedAt).isEqualTo(start)
            assertThat(loaded.finishedAt).isNull()
            assertThat(loaded.checkedCount).isEqualTo(0)
            assertThat(adapter.findById(loaded.reconciliationRunId!! + 1000)).isNull()
        }

    @Test
    fun `완료와 실패를 저장하고 감사 컬럼을 갱신한다`() =
        runBlocking<Unit> {
            val completed: ReconciliationRun = adapter.save(ReconciliationRun.start(10L, start))
            completed.complete(120, 3, start.plusMinutes(2))
            adapter.update(completed)
            val failed: ReconciliationRun = adapter.save(ReconciliationRun.start(20L, start))
            failed.fail(start.plusMinutes(1))
            adapter.update(failed)

            val a: ReconciliationRun = checkNotNull(adapter.findById(checkNotNull(completed.reconciliationRunId)))
            assertThat(a.status).isEqualTo(ReconciliationStatus.COMPLETED)
            assertThat(a.checkedCount).isEqualTo(120)
            assertThat(a.mismatchCount).isEqualTo(3)
            assertThat(a.finishedAt).isEqualTo(start.plusMinutes(2))
            val b: ReconciliationRun = checkNotNull(adapter.findById(checkNotNull(failed.reconciliationRunId)))
            assertThat(b.status).isEqualTo(ReconciliationStatus.FAILED)
            assertThat(b.finishedAt).isEqualTo(start.plusMinutes(1))
            val updatedBy: String =
                databaseClient
                    .sql("SELECT updated_by FROM reconciliation_run WHERE reconciliation_run_id = ${completed.reconciliationRunId}")
                    .fetch()
                    .one()
                    .awaitFirst()["updated_by"]
                    .toString()
            assertThat(updatedBy).isEqualTo(LocalActorProvider.LOCAL_PRINCIPAL_ID.toString())
        }

    @Test
    fun `이미 저장된 실행은 다시 저장할 수 없고 저장되지 않은 실행은 갱신할 수 없다`() =
        runBlocking<Unit> {
            val saved: ReconciliationRun = adapter.save(ReconciliationRun.start(10L, start))

            org.junit.jupiter.api
                .assertThrows<IllegalStateException> { runBlocking { adapter.save(saved) } }
            org.junit.jupiter.api.assertThrows<IllegalStateException> {
                runBlocking {
                    adapter.update(
                        ReconciliationRun.start(10L, start),
                    )
                }
            }
        }

    @Test
    fun `ReconciliationStatus와 DB CHECK 제약의 값이 일치한다`() =
        runBlocking<Unit> {
            val clause: String =
                databaseClient
                    .sql(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'ck_reconciliation_run_status'",
                    ).fetch()
                    .one()
                    .awaitFirst()["CHECK_CLAUSE"] as String
            val values: List<String> = Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()

            assertThat(values).containsExactlyInAnyOrderElementsOf(ReconciliationStatus.entries.map { it.name })
        }
}
