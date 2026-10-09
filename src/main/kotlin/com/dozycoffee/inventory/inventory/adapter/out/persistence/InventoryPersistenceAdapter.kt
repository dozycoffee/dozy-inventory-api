package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.error.DomainException
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.inventory.application.port.out.AllocationCandidate
import com.dozycoffee.inventory.inventory.application.port.out.AvailabilityRow
import com.dozycoffee.inventory.inventory.application.port.out.InventoryLotInfo
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 수량 변경은 읽고-계산하고-쓰지 않고 조건부 UPDATE 한 문장으로 한다(ADR-0003). `DatabaseClient`로 직접 쓰는 SQL은
 * Auditing이 동작하지 않아 `updated_at`, `updated_by`를 SQL에 직접 넣는다. 조건에 맞는 행이 없으면(영향 행 0)
 * 행을 다시 조회해 [Inventory]의 규칙으로 실패 원인을 판별한다.
 */
@Component
class InventoryPersistenceAdapter(
    private val inventoryR2dbcRepository: InventoryR2dbcRepository,
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) : InventoryRepository {
    override suspend fun findById(inventoryId: Long): Inventory? = inventoryR2dbcRepository.findById(inventoryId)?.toDomain()

    override suspend fun findByKey(key: InventoryKey): Inventory? =
        inventoryR2dbcRepository
            .findByWarehouseIdAndLotIdAndQualityStatus(key.warehouseId, key.lotId, key.qualityStatus)
            ?.toDomain()

    override suspend fun findAvailability(
        productIds: Set<Long>,
        warehouseIds: Set<Long>?,
    ): List<AvailabilityRow> {
        if (productIds.isEmpty() || warehouseIds?.isEmpty() == true) return emptyList()
        val sql: String = if (warehouseIds == null) AVAILABILITY else AVAILABILITY_BY_WAREHOUSES
        var spec: DatabaseClient.GenericExecuteSpec = databaseClient.sql(sql).bind("productIds", productIds.toList())
        if (warehouseIds != null) spec = spec.bind("warehouseIds", warehouseIds.toList())
        return spec
            .map { row, _ ->
                AvailabilityRow(
                    warehouseId = checkNotNull(row.get("warehouse_id", Long::class.javaObjectType)),
                    productId = checkNotNull(row.get("product_id", Long::class.javaObjectType)),
                    availableQuantity = checkNotNull(row.get("available", Long::class.javaObjectType)),
                )
            }.flow()
            .toList()
    }

    override suspend fun findAllocationCandidates(
        warehouseId: Long,
        productIds: Set<Long>,
        today: LocalDate,
    ): List<AllocationCandidate> {
        if (productIds.isEmpty()) return emptyList()
        return databaseClient
            .sql(ALLOCATION_CANDIDATES)
            .bind("warehouseId", warehouseId)
            .bind("productIds", productIds.toList())
            .bind("today", today)
            .map { row, _ ->
                AllocationCandidate(
                    productId = checkNotNull(row.get("product_id", Long::class.javaObjectType)),
                    inventoryId = checkNotNull(row.get("inventory_id", Long::class.javaObjectType)),
                    lotId = checkNotNull(row.get("lot_id", Long::class.javaObjectType)),
                    expirationDate = row.get("expiration_date", LocalDate::class.java),
                    availableQuantity = checkNotNull(row.get("available", Int::class.javaObjectType)),
                )
            }.flow()
            .toList()
    }

    override suspend fun findLotInfos(inventoryIds: Set<Long>): List<InventoryLotInfo> {
        if (inventoryIds.isEmpty()) return emptyList()
        return databaseClient
            .sql(LOT_INFOS)
            .bind("inventoryIds", inventoryIds.toList())
            .map { row, _ ->
                InventoryLotInfo(
                    inventoryId = checkNotNull(row.get("inventory_id", Long::class.javaObjectType)),
                    lotId = checkNotNull(row.get("lot_id", Long::class.javaObjectType)),
                    lotNumber = checkNotNull(row.get("lot_number", String::class.java)),
                    expirationDate = row.get("expiration_date", LocalDate::class.java),
                )
            }.flow()
            .toList()
    }

    override suspend fun increase(
        key: InventoryKey,
        productId: Long,
        amount: Int,
    ): Inventory {
        Inventory.requireValidAmount(amount)
        databaseClient
            .sql(INSERT_OR_INCREASE)
            .bind("warehouseId", key.warehouseId)
            .bind("productId", productId)
            .bind("lotId", key.lotId)
            .bind("qualityStatus", key.qualityStatus.name)
            .bind("amount", amount)
            .bindAudit()
            .fetch()
            .awaitRowsUpdated()
        return checkNotNull(findByKey(key)) { "증가한 재고 행을 찾을 수 없다: $key" }
    }

    override suspend fun decrease(
        inventoryId: Long,
        amount: Int,
    ): Inventory {
        Inventory.requireValidAmount(amount)
        return update(inventoryId, DECREASE, amount, ::InsufficientAvailableQuantityException) { it.decrease(amount) }
    }

    override suspend fun reserve(
        inventoryId: Long,
        amount: Int,
    ): Inventory {
        Inventory.requireValidAmount(amount)
        return update(inventoryId, RESERVE, amount, ::InsufficientAvailableQuantityException) { it.checkReservable(amount) }
    }

    override suspend fun release(
        inventoryId: Long,
        amount: Int,
    ): Inventory {
        Inventory.requireValidAmount(amount)
        return update(inventoryId, RELEASE, amount, ::InsufficientReservedQuantityException) { it.release(amount) }
    }

    override suspend fun ship(
        inventoryId: Long,
        amount: Int,
    ): Inventory {
        Inventory.requireValidAmount(amount)
        return update(inventoryId, SHIP, amount, ::InsufficientReservedQuantityException) { it.ship(amount) }
    }

    override suspend fun holdAllocation(
        inventoryId: Long,
        reason: String?,
        at: LocalDateTime,
    ): Inventory {
        val validReason: String = Inventory.requireValidHoldReason(reason)
        databaseClient
            .sql(HOLD)
            .bind("inventoryId", inventoryId)
            .bind("reason", validReason)
            .bind("heldAt", at)
            .bindAudit()
            .fetch()
            .awaitRowsUpdated()
        return findById(inventoryId) ?: throw InventoryNotFoundException()
    }

    override suspend fun releaseAllocationHold(inventoryId: Long): Inventory {
        databaseClient
            .sql(RELEASE_HOLD)
            .bind("inventoryId", inventoryId)
            .bindAudit()
            .fetch()
            .awaitRowsUpdated()
        return findById(inventoryId) ?: throw InventoryNotFoundException()
    }

    /**
     * 조건부 UPDATE를 실행하고 영향 행이 0이면 [diagnose]가 던지는 도메인 예외로 원인을 알린다.
     * 조회한 시점에는 조건을 만족하는 상태라면 그 사이 다른 요청이 행을 바꾼 것이므로 [fallback] 예외로 알려 호출자가 다시 시도하게 한다.
     */
    private suspend fun update(
        inventoryId: Long,
        sql: String,
        amount: Int,
        fallback: () -> DomainException,
        diagnose: (Inventory) -> Unit,
    ): Inventory {
        val updated: Long =
            databaseClient
                .sql(sql)
                .bind("inventoryId", inventoryId)
                .bind("amount", amount)
                .bindAudit()
                .fetch()
                .awaitRowsUpdated()
        if (updated == 0L) {
            val current: Inventory = findById(inventoryId) ?: throw InventoryNotFoundException()
            diagnose(current)
            throw fallback()
        }
        return checkNotNull(findById(inventoryId)) { "갱신한 재고 행을 찾을 수 없다: $inventoryId" }
    }

    private suspend fun DatabaseClient.GenericExecuteSpec.bindAudit(): DatabaseClient.GenericExecuteSpec =
        bind("now", LocalDateTime.now(clock)).bind("actor", currentActorProvider.get().auditName)

    private companion object {
        const val AVAILABILITY: String =
            """
            SELECT warehouse_id, product_id,
                   CAST(SUM(CASE WHEN quality_status = 'NORMAL' AND allocation_hold = 0 THEN quantity - reserved_quantity ELSE 0 END) AS SIGNED) AS available
              FROM inventory
             WHERE product_id IN (:productIds)
             GROUP BY warehouse_id, product_id
             ORDER BY product_id, warehouse_id
            """

        const val AVAILABILITY_BY_WAREHOUSES: String =
            """
            SELECT warehouse_id, product_id,
                   CAST(SUM(CASE WHEN quality_status = 'NORMAL' AND allocation_hold = 0 THEN quantity - reserved_quantity ELSE 0 END) AS SIGNED) AS available
              FROM inventory
             WHERE product_id IN (:productIds)
               AND warehouse_id IN (:warehouseIds)
             GROUP BY warehouse_id, product_id
             ORDER BY product_id, warehouse_id
            """

        const val ALLOCATION_CANDIDATES: String =
            """
            SELECT i.product_id, i.inventory_id, i.lot_id, l.expiration_date, i.quantity - i.reserved_quantity AS available
              FROM inventory i
              JOIN lot l ON l.lot_id = i.lot_id
             WHERE i.warehouse_id = :warehouseId
               AND i.product_id IN (:productIds)
               AND i.quality_status = 'NORMAL'
               AND i.allocation_hold = 0
               AND i.quantity - i.reserved_quantity > 0
               AND (l.expiration_date IS NULL OR l.expiration_date > :today)
             ORDER BY i.product_id, l.expiration_date IS NULL, l.expiration_date, i.inventory_id
            """

        const val LOT_INFOS: String =
            """
            SELECT i.inventory_id, i.lot_id, l.lot_number, l.expiration_date
              FROM inventory i
              JOIN lot l ON l.lot_id = i.lot_id
             WHERE i.inventory_id IN (:inventoryIds)
             ORDER BY i.inventory_id
            """

        const val INSERT_OR_INCREASE: String =
            """
            INSERT INTO inventory (warehouse_id, product_id, lot_id, quality_status, quantity, reserved_quantity, allocation_hold,
                                   created_at, created_by, updated_at, updated_by)
            VALUES (:warehouseId, :productId, :lotId, :qualityStatus, :amount, 0, 0, :now, :actor, :now, :actor) AS new_row
            ON DUPLICATE KEY UPDATE quantity = inventory.quantity + new_row.quantity, updated_at = :now, updated_by = :actor
            """

        const val DECREASE: String =
            """
            UPDATE inventory
               SET quantity = quantity - :amount, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND quantity - reserved_quantity >= :amount
            """

        const val RESERVE: String =
            """
            UPDATE inventory
               SET reserved_quantity = reserved_quantity + :amount, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND quality_status = 'NORMAL'
               AND allocation_hold = 0
               AND quantity - reserved_quantity >= :amount
            """

        const val RELEASE: String =
            """
            UPDATE inventory
               SET reserved_quantity = reserved_quantity - :amount, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND reserved_quantity >= :amount
            """

        const val SHIP: String =
            """
            UPDATE inventory
               SET quantity = quantity - :amount, reserved_quantity = reserved_quantity - :amount, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND reserved_quantity >= :amount
            """

        const val HOLD: String =
            """
            UPDATE inventory
               SET allocation_hold = 1, hold_reason = :reason, held_at = :heldAt, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND allocation_hold = 0
            """

        const val RELEASE_HOLD: String =
            """
            UPDATE inventory
               SET allocation_hold = 0, hold_reason = NULL, held_at = NULL, updated_at = :now, updated_by = :actor
             WHERE inventory_id = :inventoryId
               AND allocation_hold = 1
            """
    }
}
