package com.dozycoffee.inventory.access.adapter.out.persistence

import com.dozycoffee.inventory.access.application.port.out.WarehouseAccessRepository
import com.dozycoffee.inventory.access.domain.model.WarehouseAccess
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 소속 사본은 `DatabaseClient`로 직접 쓴다. Auditing이 동작하지 않아 감사 컬럼을 SQL에 넣고, 이미 있는 행은 `ON DUPLICATE KEY UPDATE`로
 * 수신 시각(`updated_at`)만 갱신해 같은 이벤트를 다시 받아도 안전하다. 직원 한 명의 이벤트는 같은 파티션이라 순서대로 처리된다.
 */
@Component
class WarehouseAccessPersistenceAdapter(
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) : WarehouseAccessRepository {
    override suspend fun findWarehouseIds(principalId: PrincipalId): Set<Long> =
        databaseClient
            .sql(FIND)
            .bind("principalId", principalId.value)
            .map { row, _ -> checkNotNull(row.get("warehouse_id", Long::class.javaObjectType)) }
            .flow()
            .toList()
            .toSet()

    override suspend fun replace(access: WarehouseAccess) {
        val now: LocalDateTime = LocalDateTime.now(clock)
        val actor: String = currentActorProvider.get().auditName
        if (access.warehouseIds.isEmpty()) {
            databaseClient
                .sql(DELETE_ALL)
                .bind("principalId", access.principalId.value)
                .fetch()
                .awaitRowsUpdated()
            return
        }
        databaseClient
            .sql(DELETE_OTHERS)
            .bind("principalId", access.principalId.value)
            .bind("warehouseIds", access.warehouseIds.toList())
            .fetch()
            .awaitRowsUpdated()
        access.warehouseIds.sorted().forEach { warehouseId: Long ->
            databaseClient
                .sql(UPSERT)
                .bind("principalId", access.principalId.value)
                .bind("warehouseId", warehouseId)
                .bind("now", now)
                .bind("actor", actor)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private companion object {
        const val FIND: String = "SELECT warehouse_id FROM warehouse_access WHERE principal_id = :principalId"

        const val DELETE_ALL: String = "DELETE FROM warehouse_access WHERE principal_id = :principalId"

        const val DELETE_OTHERS: String =
            "DELETE FROM warehouse_access WHERE principal_id = :principalId AND warehouse_id NOT IN (:warehouseIds)"

        const val UPSERT: String =
            """
            INSERT INTO warehouse_access (principal_id, warehouse_id, created_at, created_by, updated_at, updated_by)
            VALUES (:principalId, :warehouseId, :now, :actor, :now, :actor)
            ON DUPLICATE KEY UPDATE updated_at = :now, updated_by = :actor
            """
    }
}
