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

@InventoryIntegrationTest
class InventoryProductIndexMigrationTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Nested
    inner class `상품 기준 조회 인덱스(V4)` {
        @Test
        fun `inventory에 product_id를 앞에 둔 인덱스가 있다`() =
            runBlocking<Unit> {
                val columns: List<String> =
                    databaseClient
                        .sql(
                            """
                            SELECT column_name AS name FROM information_schema.statistics
                            WHERE table_schema = DATABASE() AND table_name = 'inventory' AND index_name = 'idx_inventory_product'
                            ORDER BY seq_in_index
                            """.trimIndent(),
                        ).map { row, _ -> row.get("name", String::class.java).orEmpty() }
                        .all()
                        .asFlow()
                        .toList()

                assertThat(columns).containsExactly("product_id", "warehouse_id")
            }
    }
}
