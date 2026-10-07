package com.dozycoffee.inventory.schema

import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient

/** 마이그레이션 세 파일(V1 테이블, V2 인덱스, V3 외래키)이 기대한 구조를 만드는지 실제 MySQL에서 확인한다 */
@InventoryIntegrationTest
class MigrationStructureTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private suspend fun rows(
        sql: String,
        vararg columns: String,
    ): List<List<String>> =
        databaseClient
            .sql(sql)
            .map { row, _ -> columns.map { row.get(it, String::class.java).orEmpty() } }
            .all()
            .asFlow()
            .toList()

    @Nested
    inner class `인덱스(V2)` {
        /** 유니크 제약과 기본키가 만드는 인덱스를 뺀 일반 인덱스. 외래키가 자동으로 만든 인덱스가 끼면 이 목록과 달라진다 */
        private val expected: Map<String, String> =
            mapOf(
                "idx_inventory_lot_product" to "lot_id,product_id",
                "idx_reservation_item_product" to "product_id",
                "idx_allocation_inventory" to "inventory_id",
                "idx_adjustment_run" to "reconciliation_run_id",
                "idx_adjustment_item_lot" to "lot_id",
                "idx_adjustment_item_inventory" to "inventory_id",
                "idx_lot_expiration_date" to "expiration_date",
                "idx_inventory_available" to "warehouse_id,product_id,quality_status",
                "idx_history_inventory_created" to "inventory_id,created_at",
                "idx_history_reference" to "reference_type,reference_id",
                "idx_reservation_expiry" to "status,expires_at",
                "idx_reservation_order" to "channel,external_order_id",
                "idx_reservation_event" to "reservation_id,created_at",
                "idx_reconciliation_run" to "warehouse_id,started_at",
                "idx_outbox_pending" to "status,outbox_event_id",
                "idx_inventory_product" to "product_id,warehouse_id",
            )

        @Test
        fun `일반 인덱스는 기대한 이름과 컬럼 순서와 같고 외래키가 자동으로 만든 인덱스는 없다`() =
            runBlocking<Unit> {
                val actual: Map<String, String> =
                    rows(
                        """
                        SELECT index_name AS name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND non_unique = 1 AND table_name <> 'flyway_schema_history'
                        GROUP BY table_name, index_name
                        """.trimIndent(),
                        "name",
                        "cols",
                    ).associate { (name: String, cols: String) -> name to cols }

                assertThat(actual).isEqualTo(expected)
            }
    }

    @Nested
    inner class `외래키(V3)` {
        @Test
        fun `외래키 12개가 만들어진다`() =
            runBlocking<Unit> {
                val names: List<String> =
                    rows(
                        "SELECT constraint_name AS name FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()",
                        "name",
                    ).map { it.single() }

                assertThat(names).hasSize(12).contains("fk_inventory_lot_product", "fk_history_inventory", "fk_lot_product")
            }
    }

    @Nested
    inner class `컬럼 이름(V1)` {
        @Test
        fun `조정 항목에는 inventory_quantity와 wms_quantity가 있고 ims_quantity는 없다`() =
            runBlocking<Unit> {
                val columns: List<String> =
                    rows(
                        "SELECT column_name AS name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'stock_adjustment_item'",
                        "name",
                    ).map { it.single() }

                assertThat(columns).contains("inventory_quantity", "wms_quantity").doesNotContain("ims_quantity")
            }
    }
}
