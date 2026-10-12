package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.LocalDate
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 대사 보정안 조회·승인·거부 API를 검증한다. WMS는 포트를 대체한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class ReconciliationApiIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var reconcileStockUseCase: ReconcileStockUseCase

    @MockitoBean
    private lateinit var wmsStockPort: WmsStockPort

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotA: Long = 0L
    private var lotB: Long = 0L
    private var proposalA: Long = 0L
    private var proposalB: Long = 0L
    private val managerId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            lotA = checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId)
            lotB = checkNotNull(fixture.seedLot(productId, "B", LocalDate.of(2027, 6, 1)).lotId)
            fixture.insertInventory(10L, productId, lotA, quantity = 100)
            fixture.insertInventory(10L, productId, lotB, quantity = 500)
            fixture.insertInventory(20L, productId, lotA, quantity = 50)
            // 창고 10: A 100→92(임계치 이하), B 500→300(임계치 초과). 창고 20: A 50→49
            whenever(wmsStockPort.findQuantities(10L, null, null)).thenReturn(listOf(wms("A", 92), wms("B", 300)))
            whenever(wmsStockPort.findQuantities(20L, null, null)).thenReturn(listOf(wms("A", 49)))
            whenever(wmsStockPort.findQuantities(10L, productId, "A")).thenReturn(listOf(wms("A", 92)))
            whenever(wmsStockPort.findQuantities(10L, productId, "B")).thenReturn(listOf(wms("B", 300)))
            ActorContext.with(SystemActor) { reconcileStockUseCase.reconcile() }
            proposalA = idOf(10L, lotA)
            proposalB = idOf(10L, lotB)
            databaseClient
                .sql(
                    """
                    INSERT INTO warehouse_access (principal_id, warehouse_id, created_at, created_by, updated_at, updated_by)
                    VALUES ('$managerId', 10, NOW(6), 'test', NOW(6), 'test')
                    """,
                ).fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun wms(
        lot: String,
        quantity: Int,
    ): WmsStockQuantity = WmsStockQuantity(productId, lot, QualityStatus.NORMAL, quantity)

    private suspend fun idOf(
        warehouseId: Long,
        lotId: Long,
    ): Long =
        scalar(
            "SELECT a.stock_adjustment_id FROM stock_adjustment a " +
                "JOIN stock_adjustment_item i ON i.stock_adjustment_id = a.stock_adjustment_id " +
                "WHERE a.warehouse_id = $warehouseId AND i.lot_id = $lotId",
        ).toLong()

    private suspend fun scalar(sql: String): String =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()

    private fun token(role: String): String = tokens.issue(type = PrincipalType.SYSTEM, id = managerId, roles = listOf("inventory:$role"))

    private fun call(
        method: HttpMethod,
        uri: String,
        role: String? = "admin",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .method(method)
            .uri(uri)
            .headers { headers -> role?.let { headers.setBearerAuth(token(it)) } }
            .exchange()

    private val base: String = "/api/v1/reconciliations/proposals"

    @Nested
    inner class `조회` {
        @Test
        fun `본사 관리자는 모든 창고의 보정안을 최신순으로 보고 창고와 상태로 거른다`() {
            call(HttpMethod.GET, base)
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.items[0].warehouseId")
                .isEqualTo(20)
                .jsonPath("$.items[0].requiresAdmin")
                .isEqualTo(false)
            call(HttpMethod.GET, "$base?warehouseId=10&status=PENDING")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.items[1].lotNumber")
                .isEqualTo("A")
                .jsonPath("$.items[1].inventoryQuantity")
                .isEqualTo(100)
                .jsonPath("$.items[1].wmsQuantity")
                .isEqualTo(92)
                .jsonPath("$.items[1].quantityChange")
                .isEqualTo(-8)
                .jsonPath("$.items[0].requiresAdmin")
                .isEqualTo(true)
            call(HttpMethod.GET, "$base?status=APPLIED")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0)
        }

        @Test
        fun `창고 관리자는 배정된 창고의 보정안만 보고 다른 창고는 403이다`() {
            call(HttpMethod.GET, base, "warehouse_manager")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
            call(HttpMethod.GET, "$base?warehouseId=20", "warehouse_manager")
                .expectStatus()
                .isForbidden
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_WAREHOUSE_ACCESS_DENIED")
            call(HttpMethod.GET, "$base/$proposalA", "warehouse_manager").expectStatus().isOk
        }

        @Test
        fun `다른 창고의 보정안 상세는 403이고 없는 보정안은 404다`() =
            runBlocking<Unit> {
                val other: Long = idOf(20L, lotA)

                call(HttpMethod.GET, "$base/$other", "warehouse_manager").expectStatus().isForbidden
                call(HttpMethod.GET, "$base/$other", "admin").expectStatus().isOk
                call(HttpMethod.GET, "$base/999999", "admin")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_ADJUSTMENT_NOT_FOUND")
            }
    }

    @Nested
    inner class `승인과 거부` {
        @Test
        fun `창고 관리자가 임계치 이하 보정안을 승인하면 반영되고 승인자는 principalId다`() =
            runBlocking<Unit> {
                call(HttpMethod.POST, "$base/$proposalA/approval", "warehouse_manager")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("APPLIED")
                    .jsonPath("$.approvedBy")
                    .isEqualTo(managerId.toString())

                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("92")
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'RECONCILIATION' AND requester_service = '$managerId'",
                    ),
                ).isEqualTo("1")
                call(HttpMethod.POST, "$base/$proposalA/approval", "warehouse_manager").expectStatus().isOk
                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("92")
            }

        @Test
        fun `임계치를 넘는 보정안은 창고 관리자는 403이고 본사 관리자는 승인한다`() =
            runBlocking<Unit> {
                call(HttpMethod.POST, "$base/$proposalB/approval", "warehouse_manager")
                    .expectStatus()
                    .isForbidden
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RECONCILIATION_ADMIN_REQUIRED")
                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotB")).isEqualTo("500")

                call(HttpMethod.POST, "$base/$proposalB/approval", "admin").expectStatus().isOk

                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotB")).isEqualTo("300")
            }

        @Test
        fun `다른 창고의 보정안은 승인할 수 없다`() =
            runBlocking<Unit> {
                val other: Long = idOf(20L, lotA)

                call(HttpMethod.POST, "$base/$other/approval", "warehouse_manager").expectStatus().isForbidden
                call(HttpMethod.POST, "$base/$other/rejection", "warehouse_manager").expectStatus().isForbidden

                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $other")).isEqualTo("PENDING")
            }

        @Test
        fun `승인할 때 WMS 수량이 바뀌었으면 409이고 새 숫자로 고친 뒤 다시 승인하면 반영한다`() =
            runBlocking<Unit> {
                whenever(wmsStockPort.findQuantities(10L, productId, "A")).thenReturn(listOf(wms("A", 90)))

                call(HttpMethod.POST, "$base/$proposalA/approval", "admin")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RECONCILIATION_CHANGED")
                call(HttpMethod.GET, "$base/$proposalA", "admin")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.wmsQuantity")
                    .isEqualTo(90)
                    .jsonPath("$.quantityChange")
                    .isEqualTo(-10)
                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("100")

                call(HttpMethod.POST, "$base/$proposalA/approval", "admin").expectStatus().isOk

                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("90")
            }

        @Test
        fun `승인할 때 WMS 조회가 실패하면 502이고 아무것도 바꾸지 않는다`() =
            runBlocking<Unit> {
                whenever(wmsStockPort.findQuantities(10L, productId, "A")).thenThrow(WmsUnavailableException())

                call(HttpMethod.POST, "$base/$proposalA/approval", "admin")
                    .expectStatus()
                    .isEqualTo(502)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_WMS_UNAVAILABLE")

                assertThat(scalar("SELECT status FROM stock_adjustment WHERE stock_adjustment_id = $proposalA")).isEqualTo("PENDING")
            }

        @Test
        fun `거부하면 수량은 그대로이고 거부된 보정안은 승인할 수 없다`() =
            runBlocking<Unit> {
                call(HttpMethod.POST, "$base/$proposalA/rejection", "warehouse_manager")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("REJECTED")

                call(HttpMethod.POST, "$base/$proposalA/approval", "admin")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_ADJUSTMENT_STATE")
                assertThat(scalar("SELECT quantity FROM inventory WHERE warehouse_id = 10 AND lot_id = $lotA")).isEqualTo("100")
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `service role은 403이고 토큰이 없으면 401이다`() {
            listOf(
                HttpMethod.GET to base,
                HttpMethod.POST to "$base/1/approval",
                HttpMethod.POST to "$base/1/rejection",
            ).forEach { (method, uri) ->
                call(method, uri, "service").expectStatus().isForbidden
                call(method, uri, null).expectStatus().isUnauthorized
            }
        }

        @Test
        fun `소속 사본이 없는 창고 관리자는 모두 403이다`() =
            runBlocking<Unit> {
                databaseClient
                    .sql("DELETE FROM warehouse_access")
                    .fetch()
                    .rowsUpdated()
                    .awaitSingle()

                call(HttpMethod.GET, base, "warehouse_manager").expectStatus().isForbidden
                call(HttpMethod.POST, "$base/$proposalA/approval", "warehouse_manager").expectStatus().isForbidden
            }
    }
}
