package com.dozycoffee.inventory.adjustment.adapter.out.persistence

import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
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
        const val ASSIGN_INVENTORY_ID: String =
            """
            UPDATE stock_adjustment_item
               SET inventory_id = :inventoryId, updated_at = :now, updated_by = :actor
             WHERE stock_adjustment_item_id = :itemId
            """
    }
}
