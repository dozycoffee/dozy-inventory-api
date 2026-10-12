package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/**
 * 조정과 항목은 테이블이 둘이고 R2DBC에는 cascade가 없어 저장 순서를 직접 처리한다(조정 → 항목, 항목은 Lot, 품질 상태 순서).
 * 새 조정만 저장할 수 있으며 같은 멱등 키가 이미 있으면 [DuplicateAdjustmentKeyException]이다.
 */
@Component
class StockAdjustmentPersistenceAdapter(
    private val stockAdjustmentR2dbcRepository: StockAdjustmentR2dbcRepository,
    private val stockAdjustmentItemR2dbcRepository: StockAdjustmentItemR2dbcRepository,
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) : StockAdjustmentRepository {
    override suspend fun save(adjustment: StockAdjustment): StockAdjustment {
        check(adjustment.stockAdjustmentId == null) { "조정은 새로 저장만 할 수 있다" }
        val saved: StockAdjustmentEntity =
            translatingDuplicateKey(duplicate = { DuplicateAdjustmentKeyException() }) {
                stockAdjustmentR2dbcRepository.save(StockAdjustmentEntity.from(adjustment))
            }
        val adjustmentId: Long = checkNotNull(saved.stockAdjustmentId) { "저장된 조정은 식별자가 있어야 한다" }
        val items: List<StockAdjustmentItem> =
            adjustment.items
                .sortedWith(compareBy({ it.lotId }, { it.qualityStatus }))
                .map { stockAdjustmentItemR2dbcRepository.save(StockAdjustmentItemEntity.from(adjustmentId, it)).toDomain() }
        return saved.toDomain(items)
    }

    override suspend fun findById(stockAdjustmentId: Long): StockAdjustment? =
        stockAdjustmentR2dbcRepository.findById(stockAdjustmentId)?.let { load(it) }

    override suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): StockAdjustment? =
        stockAdjustmentR2dbcRepository.findByIdempotencyKey(idempotencyKey.value)?.let { load(it) }

    override suspend fun findPendingReconciliationTargets(warehouseId: Long): Set<Pair<Long, QualityStatus>> =
        databaseClient
            .sql(FIND_PENDING_TARGETS)
            .bind("warehouseId", warehouseId)
            .map { row, _ ->
                checkNotNull(row.get("lot_id", Long::class.javaObjectType)) to
                    QualityStatus.valueOf(checkNotNull(row.get("quality_status", String::class.java)))
            }.flow()
            .toList()
            .toSet()

    override suspend fun updateState(
        adjustment: StockAdjustment,
        expectedStatus: AdjustmentStatus,
    ): Boolean {
        val adjustmentId: Long = checkNotNull(adjustment.stockAdjustmentId) { "저장되지 않은 조정의 상태는 갱신할 수 없다" }
        val approvedBy: String? = adjustment.approvedBy
        val approvedAt: LocalDateTime? = adjustment.approvedAt
        var spec: DatabaseClient.GenericExecuteSpec =
            databaseClient
                .sql(UPDATE_STATE)
                .bind("status", adjustment.status.name)
                .bind("now", LocalDateTime.now(clock))
                .bind("actor", currentActorProvider.get().auditName)
                .bind("adjustmentId", adjustmentId)
                .bind("expectedStatus", expectedStatus.name)
        spec = if (approvedBy == null) spec.bindNull("approvedBy", String::class.java) else spec.bind("approvedBy", approvedBy)
        spec = if (approvedAt == null) spec.bindNull("approvedAt", LocalDateTime::class.java) else spec.bind("approvedAt", approvedAt)
        return spec.fetch().awaitRowsUpdated() > 0
    }

    override suspend fun updateReconciliationItem(item: StockAdjustmentItem) {
        databaseClient
            .sql(UPDATE_ITEM_QUANTITIES)
            .bind("inventoryQuantity", checkNotNull(item.inventoryQuantity) { "대사 보정안 항목은 inventory 수량이 있어야 한다" })
            .bind("wmsQuantity", checkNotNull(item.wmsQuantity) { "대사 보정안 항목은 WMS 수량이 있어야 한다" })
            .bind("quantityChange", item.quantityChange)
            .bind("now", LocalDateTime.now(clock))
            .bind("actor", currentActorProvider.get().auditName)
            .bind("itemId", checkNotNull(item.stockAdjustmentItemId) { "저장되지 않은 항목은 갱신할 수 없다" })
            .fetch()
            .awaitRowsUpdated()
    }

    override suspend fun searchReconciliations(
        warehouseIds: Set<Long>?,
        status: AdjustmentStatus?,
        offset: Long,
        limit: Int,
    ): List<StockAdjustment> {
        if (warehouseIds?.isEmpty() == true) return emptyList()
        val filter: Filter = reconciliationFilter(warehouseIds, status)
        val ids: List<Long> =
            filter
                .bindAll(
                    databaseClient.sql(SEARCH_IDS_HEAD + filter.where + " ORDER BY stock_adjustment_id DESC LIMIT :limit OFFSET :offset"),
                ).bind("limit", limit)
                .bind("offset", offset)
                .map { row, _ -> checkNotNull(row.get("stock_adjustment_id", Long::class.javaObjectType)) }
                .flow()
                .toList()
        return ids.mapNotNull { findById(it) }
    }

    override suspend fun countReconciliations(
        warehouseIds: Set<Long>?,
        status: AdjustmentStatus?,
    ): Long {
        if (warehouseIds?.isEmpty() == true) return 0L
        val filter: Filter = reconciliationFilter(warehouseIds, status)
        return filter
            .bindAll(databaseClient.sql(COUNT_HEAD + filter.where))
            .map { row, _ -> checkNotNull(row.get("c", Long::class.javaObjectType)) }
            .one()
            .awaitSingle()
    }

    private fun reconciliationFilter(
        warehouseIds: Set<Long>?,
        status: AdjustmentStatus?,
    ): Filter {
        val clauses: MutableList<String> = mutableListOf("adjustment_type = 'RECONCILIATION'")
        val values: MutableMap<String, Any> = linkedMapOf()
        if (warehouseIds != null) {
            clauses += "warehouse_id IN (:warehouseIds)"
            values["warehouseIds"] = warehouseIds.toList()
        }
        if (status != null) {
            clauses += "status = :status"
            values["status"] = status.name
        }
        return Filter(clauses.joinToString(separator = " AND ", prefix = " WHERE "), values)
    }

    private class Filter(
        val where: String,
        private val values: Map<String, Any>,
    ) {
        fun bindAll(spec: DatabaseClient.GenericExecuteSpec): DatabaseClient.GenericExecuteSpec =
            values.entries.fold(spec) { acc, (name, value) -> acc.bind(name, value) }
    }

    /** `DatabaseClient`로 직접 쓰는 SQL은 Auditing이 동작하지 않아 `updated_at`, `updated_by`를 SQL에 직접 넣는다 */
    override suspend fun assignInventoryIds(inventoryIdsByItemId: Map<Long, Long>) {
        inventoryIdsByItemId.toSortedMap().forEach { (itemId: Long, inventoryId: Long) ->
            databaseClient
                .sql(ASSIGN_INVENTORY_ID)
                .bind("inventoryId", inventoryId)
                .bind("now", LocalDateTime.now(clock))
                .bind("actor", currentActorProvider.get().auditName)
                .bind("itemId", itemId)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private suspend fun load(entity: StockAdjustmentEntity): StockAdjustment {
        val adjustmentId: Long = checkNotNull(entity.stockAdjustmentId) { "저장된 조정은 식별자가 있어야 한다" }
        val items: List<StockAdjustmentItem> =
            stockAdjustmentItemR2dbcRepository
                .findAllByStockAdjustmentIdOrderByStockAdjustmentItemIdAsc(adjustmentId)
                .toList()
                .map(StockAdjustmentItemEntity::toDomain)
        return entity.toDomain(items)
    }

    private companion object {
        const val FIND_PENDING_TARGETS: String =
            """
            SELECT i.lot_id, i.quality_status
              FROM stock_adjustment_item i
              JOIN stock_adjustment a ON a.stock_adjustment_id = i.stock_adjustment_id
             WHERE a.adjustment_type = 'RECONCILIATION' AND a.status = 'PENDING' AND a.warehouse_id = :warehouseId
            """

        const val UPDATE_STATE: String =
            """
            UPDATE stock_adjustment
               SET status = :status, approved_by = :approvedBy, approved_at = :approvedAt, updated_at = :now, updated_by = :actor
             WHERE stock_adjustment_id = :adjustmentId AND status = :expectedStatus
            """

        const val UPDATE_ITEM_QUANTITIES: String =
            """
            UPDATE stock_adjustment_item
               SET inventory_quantity = :inventoryQuantity, wms_quantity = :wmsQuantity, quantity_change = :quantityChange,
                   updated_at = :now, updated_by = :actor
             WHERE stock_adjustment_item_id = :itemId
            """

        const val SEARCH_IDS_HEAD: String = "SELECT stock_adjustment_id FROM stock_adjustment"

        const val COUNT_HEAD: String = "SELECT COUNT(*) AS c FROM stock_adjustment"

        const val ASSIGN_INVENTORY_ID: String =
            """
            UPDATE stock_adjustment_item
               SET inventory_id = :inventoryId, updated_at = :now, updated_by = :actor
             WHERE stock_adjustment_item_id = :itemId
            """
    }
}
