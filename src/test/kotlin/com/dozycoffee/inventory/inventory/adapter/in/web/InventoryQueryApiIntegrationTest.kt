package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.LocalDate
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 관리자 재고 현황·상세·이력 조회 API를 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class InventoryQueryApiIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var normalRow: Long = 0L
    private var defectiveRow: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            val lotId: Long = checkNotNull(fixture.seedLot(productId, "LOT-A", LocalDate.of(2027, 3, 1)).lotId)
            normalRow = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 100, reservedQuantity = 30)
            defectiveRow = fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 9, hold = true)
            fixture.insertInventory(20L, productId, lotId, "NORMAL", quantity = 50)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun token(role: String): String =
        tokens.issue(
            type = PrincipalType.SYSTEM,
            id = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"),
            roles = listOf("inventory:$role"),
        )

    private fun get(
        uri: String,
        role: String? = "admin",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(uri)
            .headers { headers -> role?.let { headers.setBearerAuth(token(it)) } }
            .exchange()

    @Nested
    inner class `재고 현황` {
        @Test
        fun `조건 없이 부르면 전체를 창고 순서로 페이지로 준다`() {
            get("/api/v1/inventories?size=2")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(2)
                .jsonPath("$.items[0].warehouseId")
                .isEqualTo(10)
                .jsonPath("$.items[0].lotNumber")
                .isEqualTo("LOT-A")
                .jsonPath("$.items[0].expirationDate")
                .isEqualTo("2027-03-01")
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.totalPages")
                .isEqualTo(2)
            get("/api/v1/inventories?size=2&page=1")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].warehouseId")
                .isEqualTo(20)
        }

        @Test
        fun `조건으로 거르고 가용 수량과 보류를 함께 준다`() {
            get("/api/v1/inventories?warehouseId=10&allocationHold=true")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].inventoryId")
                .isEqualTo(defectiveRow)
                .jsonPath("$.items[0].qualityStatus")
                .isEqualTo("DEFECTIVE")
                .jsonPath("$.items[0].holdReason")
                .isEqualTo("테스트 보류")
            get("/api/v1/inventories?qualityStatus=NORMAL&productId=$productId")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.items[0].availableQuantity")
                .isEqualTo(70)
        }

        @Test
        fun `잘못된 조건은 400이다`() {
            get(
                "/api/v1/inventories?size=0",
            ).expectStatus().isBadRequest.expectBody().jsonPath("$.code").isEqualTo("INV_INVALID_INVENTORY_QUERY")
            get("/api/v1/inventories?page=-1").expectStatus().isBadRequest
            get("/api/v1/inventories?qualityStatus=UNKNOWN").expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `상세와 이력` {
        @Test
        fun `상세는 Lot과 수량과 예약을 합쳐 주고 없는 행은 404다`() {
            get("/api/v1/inventories/$normalRow")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.quantity")
                .isEqualTo(100)
                .jsonPath("$.reservedQuantity")
                .isEqualTo(30)
                .jsonPath("$.availableQuantity")
                .isEqualTo(70)
                .jsonPath("$.lotStatus")
                .isEqualTo("NORMAL")
                .jsonPath("$.allocationHold")
                .isEqualTo(false)
            get("/api/v1/inventories/999999")
                .expectStatus()
                .isNotFound
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVENTORY_NOT_FOUND")
        }

        @Test
        fun `이력은 최신순으로 페이지를 나눠 주고 재고 행이 없으면 404다`() =
            runBlocking<Unit> {
                (1..3).forEach { i ->
                    databaseClient
                        .sql(
                            """
                            INSERT INTO inventory_history (inventory_id, history_type, quantity_change, quantity_after, reference_type, reference_id,
                                                           idempotency_key, requester_service, created_at, created_by)
                            VALUES ($normalRow, 'INBOUND', $i, ${100 + i}, 'INBOUND_ITEM', $i, 'key-$i', 'svc-wms', NOW(6), 'test')
                            """,
                        ).fetch()
                        .rowsUpdated()
                        .block()
                }

                get("/api/v1/inventories/$normalRow/histories?size=2")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items.length()")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].referenceId")
                    .isEqualTo(3)
                    .jsonPath("$.items[1].referenceId")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].historyType")
                    .isEqualTo("INBOUND")
                    .jsonPath("$.totalElements")
                    .isEqualTo(3)
                get("/api/v1/inventories/$normalRow/histories?size=2&page=1")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items.length()")
                    .isEqualTo(1)
                    .jsonPath("$.items[0].referenceId")
                    .isEqualTo(1)
                get("/api/v1/inventories/$defectiveRow/histories")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(0)
                get("/api/v1/inventories/999999/histories").expectStatus().isNotFound
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `admin만 조회할 수 있다`() {
            listOf("/api/v1/inventories", "/api/v1/inventories/$normalRow", "/api/v1/inventories/$normalRow/histories").forEach { uri ->
                get(uri, "service").expectStatus().isForbidden
                get(uri, "warehouse_manager").expectStatus().isForbidden
                get(uri, null).expectStatus().isUnauthorized
            }
        }

        @Test
        fun `가용 재고 조회 경로는 그대로 service도 호출할 수 있다`() {
            get("/api/v1/inventories/availability?productIds=$productId", "service").expectStatus().isOk
        }
    }
}
