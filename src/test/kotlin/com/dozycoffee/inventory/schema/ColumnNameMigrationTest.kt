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
class ColumnNameMigrationTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private suspend fun columns(table: String): List<String> =
        databaseClient
            .sql("SELECT column_name AS name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :table")
            .bind("table", table)
            .map { row, _ -> row.get("name", String::class.java).orEmpty() }
            .all()
            .asFlow()
            .toList()

    @Nested
    inner class `서비스 이름 변경(V3)` {
        @Test
        fun `조정 항목의 ims_quantity 컬럼은 inventory_quantity로 바뀌었다`() =
            runBlocking<Unit> {
                val columns: List<String> = columns("stock_adjustment_item")

                assertThat(columns).contains("inventory_quantity", "wms_quantity")
                assertThat(columns).doesNotContain("ims_quantity")
            }
    }
}
