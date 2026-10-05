package com.dozycoffee.ims.schema

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated

@SpringBootTest
class SchemaConstraintTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @AfterEach
    fun cleanUp() =
        runBlocking {
            TABLES_IN_DELETE_ORDER.forEach { execute("DELETE FROM $it") }
        }

    @Test
    fun `마이그레이션 후 13개 테이블이 생성된다`() =
        runBlocking {
            val count: Long =
                databaseClient
                    .sql(
                        """
                        SELECT COUNT(*) AS table_count FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        """.trimIndent(),
                    ).map { row, _ -> row.get("table_count", Long::class.javaObjectType)!! }
                    .one()
                    .awaitSingle()

            assertThat(count).isEqualTo(13L)
        }

    @Test
    fun `재고 수량은 음수일 수 없다`() =
        runBlocking {
            insertProductAndLot()

            assertRejected("chk_inventory_quantity") { insertInventory(quantity = -1) }
        }

    @Test
    fun `예약 수량은 총 수량을 초과할 수 없다`() =
        runBlocking {
            insertProductAndLot()

            assertRejected("chk_inventory_reserved") { insertInventory(quantity = 5, reserved = 6) }
        }

    @Test
    fun `허용되지 않는 품질 상태는 저장할 수 없다`() =
        runBlocking {
            insertProductAndLot()

            assertRejected("ck_inventory_quality_status") { insertInventory(quality = "UNKNOWN") }
        }

    @Test
    fun `같은 창고 Lot 품질 상태의 재고 행은 중복될 수 없다`() =
        runBlocking {
            insertProductAndLot()
            insertInventory(id = 1)

            assertRejected("uq_inventory_row") { insertInventory(id = 2) }
            insertInventory(id = 3, quality = "DEFECTIVE")
        }

    @Test
    fun `할당 보류는 보류 시각이 필요하다`() =
        runBlocking {
            insertProductAndLot()

            assertRejected("chk_inventory_hold") {
                execute(inventoryInsert(id = 1, extraColumns = ", allocation_hold", extraValues = ", 1"))
            }
        }

    @Test
    fun `같은 상품에서 Lot 번호는 대소문자를 구분한다`() =
        runBlocking {
            insertProduct()
            insertLot(id = 1, number = "ab-1")
            insertLot(id = 2, number = "AB-1")

            assertRejected("uq_lot_product_number") { insertLot(id = 3, number = "ab-1") }
        }

    @Test
    fun `Lot 유통기한은 제조일자보다 빠를 수 없다`() =
        runBlocking {
            insertProduct()

            assertRejected("chk_lot_expiration_date") {
                insertLot(manufactureDate = "'2026-10-10'", expirationDate = "'2026-10-09'")
            }
        }

    @Test
    fun `재고 이력은 변동량이 0일 수 없다`() =
        runBlocking {
            insertProductAndLot()
            insertInventory()

            assertRejected("chk_history_change") { insertHistory(change = 0) }
        }

    @Test
    fun `재고 이력의 멱등 키는 같은 재고 행에서 중복될 수 없고 다른 재고 행에서는 허용된다`() =
        runBlocking {
            insertProductAndLot()
            insertInventory(id = 1)
            insertInventory(id = 2, quality = "DEFECTIVE")
            insertHistory(id = 1, inventoryId = 1, key = "key-1")

            assertRejected("uq_history_idempotency") { insertHistory(id = 2, inventoryId = 1, key = "key-1") }
            insertHistory(id = 3, inventoryId = 2, key = "key-1")
        }

    @Test
    fun `멱등 키는 대소문자를 구분한다`() =
        runBlocking {
            insertReservation(id = 1, key = "abc-1")
            insertReservation(id = 2, key = "ABC-1")

            assertRejected("uq_reservation_idempotency") { insertReservation(id = 3, key = "abc-1") }
        }

    @Test
    fun `확정 전 예약은 만료 시각이 필수이며 상한을 넘을 수 없다`() =
        runBlocking {
            assertRejected("chk_reservation_expiry") { insertReservation(id = 1, expiresAt = "NULL") }
            assertRejected("chk_reservation_expiry") {
                insertReservation(id = 2, key = "key-2", expiresAt = "DATE_ADD(NOW(6), INTERVAL 2 HOUR)")
            }

            insertReservation(id = 3, key = "key-3", status = "CONFIRMED", expiresAt = "NULL")
        }

    @Test
    fun `대사 보정은 승인자 없이 승인 또는 반영 상태가 될 수 없다`() =
        runBlocking {
            assertRejected("chk_adjustment_approval") {
                insertAdjustment(id = 1, type = "RECONCILIATION", status = "APPLIED", approvedBy = "NULL")
            }

            insertAdjustment(id = 2, key = "key-2", type = "AUDIT", status = "APPLIED", approvedBy = "NULL")
            insertAdjustment(id = 3, key = "key-3", type = "RECONCILIATION", status = "PENDING", approvedBy = "NULL")
        }

    @Test
    fun `출고된 할당 수량은 할당 수량을 초과할 수 없다`() =
        runBlocking {
            insertProductAndLot()
            insertInventory()
            insertReservation()
            insertReservationItem()

            assertRejected("chk_allocation_quantity") { insertAllocation(quantity = 3, fulfilled = 4) }
        }

    @Test
    fun `재고 행의 상품은 Lot의 상품과 일치해야 한다`() =
        runBlocking {
            insertProduct(id = 1)
            insertProduct(id = 2)
            insertLot(id = 1, productId = 1)

            assertRejected("fk_inventory_lot_product") { insertInventory(productId = 2, lotId = 1) }
        }

    @Test
    fun `예약 항목은 존재하지 않는 예약을 참조할 수 없다`() =
        runBlocking {
            insertProduct()

            assertRejected("fk_reservation_item_reservation") { insertReservationItem(reservationId = 999) }
        }

    private suspend fun execute(sql: String): Long = databaseClient.sql(sql).fetch().awaitRowsUpdated()

    private suspend fun assertRejected(
        constraint: String,
        statement: suspend () -> Unit,
    ) {
        val error: Throwable = assertThrows<Throwable> { statement() }
        val messages: String = generateSequence(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        assertThat(messages).contains(constraint)
    }

    private suspend fun insertProductAndLot() {
        insertProduct()
        insertLot()
    }

    private suspend fun insertProduct(
        id: Long = 1,
        code: String = "P-$id",
    ) {
        execute(
            """
            INSERT INTO product (product_id, product_code, product_name, category, unit, product_status, $AUDIT_COLUMNS)
            VALUES ($id, '$code', 'name', 'BEAN', 'KG', 'ACTIVE', $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private suspend fun insertLot(
        id: Long = 1,
        productId: Long = 1,
        number: String = "LOT-$id",
        manufactureDate: String = "NULL",
        expirationDate: String = "NULL",
    ) {
        execute(
            """
            INSERT INTO lot (lot_id, product_id, lot_number, manufacture_date, expiration_date, lot_status, $AUDIT_COLUMNS)
            VALUES ($id, $productId, '$number', $manufactureDate, $expirationDate, 'NORMAL', $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private fun inventoryInsert(
        id: Long,
        warehouseId: Long = 1,
        productId: Long = 1,
        lotId: Long = 1,
        quality: String = "NORMAL",
        quantity: Int = 10,
        reserved: Int = 0,
        extraColumns: String = "",
        extraValues: String = "",
    ): String =
        """
        INSERT INTO inventory (inventory_id, warehouse_id, product_id, lot_id, quality_status, quantity, reserved_quantity,
                               $AUDIT_COLUMNS$extraColumns)
        VALUES ($id, $warehouseId, $productId, $lotId, '$quality', $quantity, $reserved, $AUDIT_VALUES$extraValues)
        """.trimIndent()

    private suspend fun insertInventory(
        id: Long = 1,
        productId: Long = 1,
        lotId: Long = 1,
        quality: String = "NORMAL",
        quantity: Int = 10,
        reserved: Int = 0,
    ) {
        execute(inventoryInsert(id = id, productId = productId, lotId = lotId, quality = quality, quantity = quantity, reserved = reserved))
    }

    private suspend fun insertHistory(
        id: Long = 1,
        inventoryId: Long = 1,
        change: Int = 5,
        key: String = "key-$id",
    ) {
        execute(
            """
            INSERT INTO inventory_history (inventory_history_id, inventory_id, history_type, quantity_change, quantity_after,
                                           reference_type, reference_id, idempotency_key, requester_service, created_at, created_by)
            VALUES ($id, $inventoryId, 'INBOUND', $change, 10, 'INBOUND_ITEM', 100, '$key', 'svc-wms', NOW(6), 'test')
            """.trimIndent(),
        )
    }

    private suspend fun insertReservation(
        id: Long = 1,
        key: String = "key-$id",
        status: String = "RESERVED",
        expiresAt: String = "DATE_ADD(NOW(6), INTERVAL 30 MINUTE)",
    ) {
        execute(
            """
            INSERT INTO reservation (reservation_id, warehouse_id, channel, external_order_id, status, expires_at, max_expires_at,
                                     idempotency_key, requester_service, $AUDIT_COLUMNS)
            VALUES ($id, 1, 'OMS', 'order-$id', '$status', $expiresAt, DATE_ADD(NOW(6), INTERVAL 1 HOUR), '$key', 'svc-oms', $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private suspend fun insertReservationItem(
        id: Long = 1,
        reservationId: Long = 1,
        productId: Long = 1,
    ) {
        execute(
            """
            INSERT INTO reservation_item (reservation_item_id, reservation_id, product_id, requested_quantity, $AUDIT_COLUMNS)
            VALUES ($id, $reservationId, $productId, 5, $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private suspend fun insertAllocation(
        quantity: Int,
        fulfilled: Int,
    ) {
        execute(
            """
            INSERT INTO reservation_allocation (reservation_allocation_id, reservation_item_id, inventory_id, quantity, fulfilled_quantity,
                                                $AUDIT_COLUMNS)
            VALUES (1, 1, 1, $quantity, $fulfilled, $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private suspend fun insertAdjustment(
        id: Long,
        key: String = "key-$id",
        type: String,
        status: String,
        approvedBy: String,
    ) {
        execute(
            """
            INSERT INTO stock_adjustment (stock_adjustment_id, warehouse_id, adjustment_type, status, requested_by, approved_by,
                                          idempotency_key, $AUDIT_COLUMNS)
            VALUES ($id, 1, '$type', '$status', 'tester', $approvedBy, '$key', $AUDIT_VALUES)
            """.trimIndent(),
        )
    }

    private companion object {
        const val AUDIT_COLUMNS: String = "created_at, created_by, updated_at, updated_by"
        const val AUDIT_VALUES: String = "NOW(6), 'test', NOW(6), 'test'"

        val TABLES_IN_DELETE_ORDER: List<String> =
            listOf(
                "stock_adjustment_item",
                "stock_adjustment",
                "reconciliation_run",
                "reservation_event",
                "reservation_allocation",
                "reservation_item",
                "reservation",
                "inventory_history",
                "inventory",
                "lot",
                "product",
                "outbox_event",
                "warehouse_access",
            )
    }
}
