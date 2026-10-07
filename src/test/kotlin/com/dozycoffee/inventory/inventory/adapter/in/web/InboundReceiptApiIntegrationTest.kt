package com.dozycoffee.inventory.inventory.adapter.`in`.web

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
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 입고 확정 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class InboundReceiptApiIntegrationTest {
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
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun serviceToken(): String = tokens.issue(type = PrincipalType.SYSTEM, id = wmsPrincipalId, roles = listOf("inventory:service"))

    private fun body(
        quantity: Int = 5,
        inboundItemId: Long = 77L,
        expirationDate: String = "2027-03-01",
        product: Long = productId,
    ): String =
        """{"warehouseId":10,"productId":$product,"quantity":$quantity,"qualityStatus":"NORMAL","lotNumber":"LOT-A",
           "manufactureDate":"2026-09-01","expirationDate":"$expirationDate","inboundItemId":$inboundItemId}"""

    private fun post(
        requestBody: String,
        key: String? = "wms-inbound-item-77",
        token: String? = serviceToken(),
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/inbound-receipts")
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
        fun `service 토큰으로 입고하면 재고와 이력이 만들어지고 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                post(body())
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.quantityChange")
                    .isEqualTo(5)
                    .jsonPath("$.quantityAfter")
                    .isEqualTo(5)

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("5")
                assertThat(scalar("SELECT requester_service FROM inventory_history")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT created_by FROM inventory_history")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT reference_id FROM inventory_history")).isEqualTo("77")
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 수량을 다시 더하지 않는다`() =
            runBlocking<Unit> {
                val first: String = postForBody(body(), "wms-inbound-item-77")
                postForBody(body(quantity = 3, inboundItemId = 78L), "wms-inbound-item-78")

                val replay: String = postForBody(body(), "wms-inbound-item-77")

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("8")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("2")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 한 번만 반영하고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { postForBody(body(), "same-key") } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("5")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("1")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `기존 Lot과 유통기한이 다르면 409 INV_LOT_MISMATCH이고 아무것도 바뀌지 않는다`() =
            runBlocking<Unit> {
                postForBody(body(), "k-1")

                post(body(expirationDate = "2027-04-01", inboundItemId = 78L), "k-2")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_MISMATCH")

                assertThat(scalar("SELECT quantity FROM inventory")).isEqualTo("5")
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                postForBody(body(), "k-1")

                post(body(quantity = 6), "k-1")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `없는 상품은 404 INV_PRODUCT_NOT_FOUND`() =
            runBlocking<Unit> {
                post(body(product = 999_999L))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")
            }

        @Test
        fun `수량 규칙 위반은 400이고 멱등 키 형식 오류도 400이다`() =
            runBlocking<Unit> {
                post(body().replace("\"quantity\":5", "\"quantity\":0")).expectStatus().isBadRequest
                post(body().replace("NORMAL", "DISPOSAL_SCHEDULED"))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_INBOUND_QUALITY_STATUS")
                post(body(), key = "has space").expectStatus().isBadRequest
                post(body(), key = null).expectStatus().isBadRequest
            }
    }

    @Nested
    inner class `인증과 권한` {
        @Test
        fun `토큰이 없으면 401이고 service가 아닌 role은 403이다`() =
            runBlocking<Unit> {
                post(body(), token = null).expectStatus().isUnauthorized
                post(body(), token = tokens.issue(roles = listOf("inventory:admin"))).expectStatus().isForbidden
                post(body(), token = tokens.issue(roles = listOf("inventory:warehouse_manager"))).expectStatus().isForbidden

                assertThat(scalar("SELECT COUNT(*) FROM inventory")).isEqualTo("0")
            }
    }
}
