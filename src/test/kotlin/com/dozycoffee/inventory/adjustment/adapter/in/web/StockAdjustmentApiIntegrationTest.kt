package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 실사 조정 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class StockAdjustmentApiIntegrationTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    private val webTestClient: WebTestClient by lazy { baseClient.mutate().responseTimeout(Duration.ofSeconds(30)).build() }

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private val wmsPrincipalId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            val lotA: Long = checkNotNull(fixture.seedLot(productId, "LOT-A", LocalDate.of(2027, 1, 1)).lotId)
            fixture.seedLot(productId, "LOT-B", LocalDate.of(2027, 6, 1))
            fixture.insertInventory(10L, productId, lotA, quantity = 1000)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun token(role: String = "service"): String =
        tokens.issue(type = PrincipalType.SYSTEM, id = wmsPrincipalId, roles = listOf("inventory:$role"))

    private fun body(
        change: Int = -4,
        lot: String = "LOT-A",
        approvedBy: String? = null,
        auditId: Long = 77L,
    ): String =
        """{"warehouseId":10,"auditId":$auditId${approvedBy?.let { ""","approvedBy":"$it"""" }.orEmpty()},"items":[
           {"productId":$productId,"lotNumber":"$lot","qualityStatus":"NORMAL","quantityChange":$change}]}"""

    private fun post(
        requestBody: String,
        key: String? = "wms-audit-77",
        token: String? = token(),
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/stock-adjustments")
            .headers { headers ->
                token?.let { headers.setBearerAuth(it) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    private fun postForBody(
        requestBody: String,
        key: String,
    ): String =
        post(requestBody, key)
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .orEmpty()

    private suspend fun scalar(sql: String): String =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()

    @Nested
    inner class `전체 흐름` {
        @Test
        fun `service 토큰으로 조정하면 재고와 이력이 바뀌고 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                post(body())
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("APPLIED")
                    .jsonPath("$.auditId")
                    .isEqualTo(77)
                    .jsonPath("$.items[0].quantityChange")
                    .isEqualTo(-4)
                    .jsonPath("$.items[0].quantityAfter")
                    .isEqualTo(996)

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("996")
                assertThat(scalar("SELECT requester_service FROM inventory_history")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT requested_by FROM stock_adjustment")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT created_by FROM stock_adjustment")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT reference_type FROM inventory_history")).isEqualTo("STOCK_ADJUSTMENT_ITEM")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment_item WHERE inventory_id IS NOT NULL")).isEqualTo("1")
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 수량을 다시 바꾸지 않는다`() =
            runBlocking<Unit> {
                val first: String = postForBody(body(), "wms-audit-77")
                postForBody(body(change = -3, auditId = 78L), "wms-audit-78")

                val replay: String = postForBody(body(), "wms-audit-77")

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("993")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("2")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 한 번만 반영하고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { postForBody(body(), "same-key") } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("996")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("1")
            }
    }

    @Nested
    inner class `승인` {
        @Test
        fun `임계치를 넘는 변동에 승인자가 없으면 400 INV_APPROVAL_REQUIRED이고 아무것도 바뀌지 않는다`() =
            runBlocking<Unit> {
                post(body(change = -101))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_APPROVAL_REQUIRED")

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("1000")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("0")
            }

        @Test
        fun `승인자가 있으면 임계치를 넘어도 반영하고 승인자를 응답한다`() =
            runBlocking<Unit> {
                post(body(change = -500, approvedBy = "manager-7"))
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.approvedBy")
                    .isEqualTo("manager-7")

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("500")
                assertThat(scalar("SELECT approved_by FROM stock_adjustment")).isEqualTo("manager-7")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `등록되지 않은 Lot은 404 INV_LOT_NOT_FOUND`() =
            runBlocking<Unit> {
                post(body(lot = "NOPE"))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_NOT_FOUND")
            }

        @Test
        fun `재고 행이 없는 Lot의 감소는 404 INV_INVENTORY_NOT_FOUND`() =
            runBlocking<Unit> {
                post(body(lot = "LOT-B"))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVENTORY_NOT_FOUND")
            }

        @Test
        fun `가용 수량을 넘는 감소는 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY이고 조정이 남지 않는다`() =
            runBlocking<Unit> {
                post(body(change = -2000, approvedBy = "manager-7"))
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("1000")
                assertThat(scalar("SELECT COUNT(*) FROM stock_adjustment")).isEqualTo("0")
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                postForBody(body(), "k-1")

                post(body(change = -5), "k-1")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `변동량이 0이면 400이고 멱등 키 형식 오류도 400이다`() =
            runBlocking<Unit> {
                post(body(change = 0)).expectStatus().isBadRequest
                post(body(), key = "한글 키").expectStatus().isBadRequest
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `admin은 403이고 토큰이 없으면 401이다`() =
            runBlocking<Unit> {
                post(body(), token = token("admin")).expectStatus().isForbidden
                post(body(), token = token("warehouse_manager")).expectStatus().isForbidden
                post(body(), token = null).expectStatus().isUnauthorized

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("1000")
            }
    }
}
