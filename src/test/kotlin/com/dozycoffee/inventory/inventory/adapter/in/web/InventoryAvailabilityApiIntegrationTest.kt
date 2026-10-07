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
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 쉼표 파라미터 변환, 서비스, MySQL을 모두 거쳐 가용 재고 조회 API를 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class InventoryAvailabilityApiIntegrationTest {
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

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            val lotId: Long = requireNotNull(fixture.seedLot(productId).lotId)
            fixture.insertInventory(10L, productId, lotId, quantity = 100, reservedQuantity = 30)
            fixture.insertInventory(20L, productId, lotId, quantity = 50)
            fixture.insertInventory(20L, productId, lotId, qualityStatus = "DEFECTIVE", quantity = 9)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun token(role: String): String =
        tokens.issue(type = PrincipalType.SYSTEM, id = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"), roles = listOf(role))

    private fun get(
        query: String,
        role: String = "inventory:service",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri("/api/v1/inventories/availability?$query")
            .headers { it.setBearerAuth(token(role)) }
            .exchange()

    @Nested
    inner class `조회` {
        @Test
        fun `창고를 생략하면 재고가 있는 창고별 가용 수량과 합계를 준다`() {
            get("productIds=$productId")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.products[0].productId")
                .isEqualTo(productId)
                .jsonPath("$.products[0].totalAvailableQuantity")
                .isEqualTo(120)
                .jsonPath("$.products[0].warehouses.length()")
                .isEqualTo(2)
                .jsonPath("$.products[0].warehouses[0].warehouseId")
                .isEqualTo(10)
                .jsonPath("$.products[0].warehouses[0].availableQuantity")
                .isEqualTo(70)
                .jsonPath("$.products[0].warehouses[1].availableQuantity")
                .isEqualTo(50)
        }

        @Test
        fun `창고를 쉼표로 지정하면 재고가 없는 창고도 0으로 채운다`() {
            get("productIds=$productId&warehouseIds=20,30")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.products[0].totalAvailableQuantity")
                .isEqualTo(50)
                .jsonPath("$.products[0].warehouses.length()")
                .isEqualTo(2)
                .jsonPath("$.products[0].warehouses[1].warehouseId")
                .isEqualTo(30)
                .jsonPath("$.products[0].warehouses[1].availableQuantity")
                .isEqualTo(0)
        }

        @Test
        fun `재고가 없는 상품도 0으로 응답한다`() {
            get("productIds=999999")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.products[0].productId")
                .isEqualTo(999999)
                .jsonPath("$.products[0].totalAvailableQuantity")
                .isEqualTo(0)
        }
    }

    @Nested
    inner class `권한과 검증` {
        @Test
        fun `admin은 조회할 수 있고 warehouse_manager는 403이다`() {
            get("productIds=$productId", "inventory:admin").expectStatus().isOk
            get("productIds=$productId", "inventory:warehouse_manager").expectStatus().isForbidden
        }

        @Test
        fun `상품이 101개이면 400이다`() {
            get("productIds=${(1..101).joinToString(",")}").expectStatus().isBadRequest
        }
    }
}
