package com.dozycoffee.inventory.inventory.fixture

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.domain.model.Lot
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import java.time.LocalDate
import java.time.LocalDateTime

/** 실제 MySQL을 쓰는 테스트가 재고 행의 선행 데이터(상품, Lot)를 만들고 정리하는 도우미 */
class InventoryDbFixture(
    private val databaseClient: DatabaseClient,
    private val lotPersistenceAdapter: LotPersistenceAdapter,
) {
    suspend fun seedProduct(code: String = "BEAN-001"): Long {
        databaseClient
            .sql(
                """
                INSERT INTO product (product_code, product_name, category, unit, product_status, created_at, created_by, updated_at, updated_by)
                VALUES (:code, '원두', 'BEAN', 'KG', 'ACTIVE', NOW(6), 'test', NOW(6), 'test')
                """,
            ).bind("code", code)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return databaseClient
            .sql("SELECT product_id FROM product WHERE product_code = :code")
            .bind("code", code)
            .fetch()
            .one()
            .awaitFirst()["product_id"]
            .toString()
            .toLong()
    }

    suspend fun seedLot(
        productId: Long,
        lotNumber: String = "LOT-A",
        expirationDate: LocalDate? = LocalDate.of(2027, 3, 1),
    ): Lot =
        lotPersistenceAdapter.save(
            Lot.create(productId, lotNumber, LocalDate.of(2026, 9, 1), expirationDate, LocalDate.of(2026, 10, 7), 30),
        )

    /** 상태를 지정해 재고 행을 직접 넣는다. 보류 행은 `held_at`이 필요하다 */
    suspend fun insertInventory(
        warehouseId: Long,
        productId: Long,
        lotId: Long,
        qualityStatus: String = "NORMAL",
        quantity: Int = 10,
        reservedQuantity: Int = 0,
        hold: Boolean = false,
    ): Long {
        databaseClient
            .sql(
                """
                INSERT INTO inventory (warehouse_id, product_id, lot_id, quality_status, quantity, reserved_quantity,
                                       allocation_hold, hold_reason, held_at, created_at, created_by, updated_at, updated_by)
                VALUES (:warehouseId, :productId, :lotId, :qualityStatus, :quantity, :reserved, :hold, :reason, :heldAt,
                        NOW(6), 'test', NOW(6), 'test')
                """,
            ).bind("warehouseId", warehouseId)
            .bind("productId", productId)
            .bind("lotId", lotId)
            .bind("qualityStatus", qualityStatus)
            .bind("quantity", quantity)
            .bind("reserved", reservedQuantity)
            .bind("hold", hold)
            .let { spec -> if (hold) spec.bind("reason", "테스트 보류") else spec.bindNull("reason", String::class.java) }
            .let { spec -> if (hold) spec.bind("heldAt", HELD_AT) else spec.bindNull("heldAt", LocalDateTime::class.java) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return databaseClient
            .sql("SELECT inventory_id FROM inventory WHERE warehouse_id = :w AND lot_id = :l AND quality_status = :q")
            .bind("w", warehouseId)
            .bind("l", lotId)
            .bind("q", qualityStatus)
            .fetch()
            .one()
            .awaitFirst()["inventory_id"]
            .toString()
            .toLong()
    }

    suspend fun cleanUp() {
        listOf(
            "outbox_event",
            "stock_adjustment_item",
            "stock_adjustment",
            "reservation_event",
            "reservation_allocation",
            "reservation_item",
            "reservation",
            "inventory_history",
            "inventory",
            "lot",
            "product",
        ).forEach { table: String ->
            databaseClient
                .sql("DELETE FROM $table")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    companion object {
        val HELD_AT: LocalDateTime = LocalDateTime.of(2026, 10, 7, 9, 0)
    }
}
