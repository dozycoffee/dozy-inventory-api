package com.dozycoffee.inventory.access.adapter.out.persistence

import com.dozycoffee.inventory.access.domain.model.WarehouseAccess
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
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

@DataR2dbcTest
@ActiveProfiles("local")
@Import(WarehouseAccessPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class WarehouseAccessPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: WarehouseAccessPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val alice: PrincipalId = PrincipalId.of("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
    private val bob: PrincipalId = PrincipalId.of("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b74")

    @BeforeEach
    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            databaseClient
                .sql("DELETE FROM warehouse_access")
                .fetch()
                .rowsUpdated()
                .awaitFirst()
        }

    private fun access(
        principal: PrincipalId,
        vararg warehouseIds: Long,
    ): WarehouseAccess = WarehouseAccess.of(principal, warehouseIds.toList())

    private suspend fun updatedYear(
        principal: PrincipalId,
        warehouseId: Long,
    ): Long =
        databaseClient
            .sql("SELECT YEAR(updated_at) AS y FROM warehouse_access WHERE principal_id = :p AND warehouse_id = :w")
            .bind("p", principal.value)
            .bind("w", warehouseId)
            .fetch()
            .one()
            .awaitFirst()["y"]
            .toString()
            .toLong()

    @Test
    fun `사본이 없으면 빈 집합이다`() =
        runBlocking<Unit> {
            assertThat(adapter.findWarehouseIds(alice)).isEmpty()
        }

    @Test
    fun `처음 교체하면 창고를 추가하고 감사 컬럼을 채운다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L, 20L))

            assertThat(adapter.findWarehouseIds(alice)).containsExactlyInAnyOrder(10L, 20L)
            val createdBy: String =
                databaseClient
                    .sql("SELECT created_by FROM warehouse_access LIMIT 1")
                    .fetch()
                    .one()
                    .awaitFirst()["created_by"]
                    .toString()
            assertThat(createdBy).isEqualTo(LocalActorProvider.LOCAL_PRINCIPAL_ID.toString())
        }

    @Test
    fun `다시 교체하면 목록에 없는 창고는 지우고 새 창고는 추가한다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L, 20L))

            adapter.replace(access(alice, 20L, 30L))

            assertThat(adapter.findWarehouseIds(alice)).containsExactlyInAnyOrder(20L, 30L)
        }

    @Test
    fun `같은 목록을 다시 받아도 결과가 같고 행이 늘지 않는다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L, 20L))
            adapter.replace(access(alice, 10L, 20L))

            assertThat(adapter.findWarehouseIds(alice)).containsExactlyInAnyOrder(10L, 20L)
            val count: Long =
                databaseClient
                    .sql("SELECT COUNT(*) AS c FROM warehouse_access")
                    .fetch()
                    .one()
                    .awaitFirst()["c"]
                    .toString()
                    .toLong()
            assertThat(count).isEqualTo(2L)
        }

    @Test
    fun `빈 목록은 그 직원의 소속을 모두 지우고 다른 직원은 건드리지 않는다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L, 20L))
            adapter.replace(access(bob, 10L))

            adapter.replace(access(alice))

            assertThat(adapter.findWarehouseIds(alice)).isEmpty()
            assertThat(adapter.findWarehouseIds(bob)).containsExactly(10L)
        }

    @Test
    fun `직원마다 독립적으로 교체한다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L))
            adapter.replace(access(bob, 20L, 30L))

            adapter.replace(access(alice, 40L))

            assertThat(adapter.findWarehouseIds(alice)).containsExactly(40L)
            assertThat(adapter.findWarehouseIds(bob)).containsExactlyInAnyOrder(20L, 30L)
        }

    @Test
    fun `이미 있는 창고는 수신 시각을 갱신한다`() =
        runBlocking<Unit> {
            adapter.replace(access(alice, 10L))
            databaseClient
                .sql("UPDATE warehouse_access SET updated_at = '2000-01-01 00:00:00.000000'")
                .fetch()
                .rowsUpdated()
                .awaitFirst()
            assertThat(updatedYear(alice, 10L)).isEqualTo(2000L)

            adapter.replace(access(alice, 10L))

            assertThat(updatedYear(alice, 10L)).isGreaterThan(2000L)
        }
}
