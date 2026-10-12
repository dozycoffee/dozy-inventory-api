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
import java.time.LocalDate
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 폐기 확정 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class DisposalApiIntegrationTest {
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
            val lotId: Long = checkNotNull(fixture.seedLot(productId, "LOT-A", LocalDate.of(2027, 1, 1)).lotId)
            fixture.insertInventory(10L, productId, lotId, "DEFECTIVE", quantity = 100)
            fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 50)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun token(role: String = "service"): String =
        tokens.issue(type = PrincipalType.SYSTEM, id = wmsPrincipalId, roles = listOf("inventory:$role"))

    private fun body(
        quantity: Int = 4,
        quality: String = "DEFECTIVE",
        disposalItemId: Long = 5L,
        lot: String = "LOT-A",
    ): String =
        """{"warehouseId":10,"productId":$productId,"lotNumber":"$lot","qualityStatus":"$quality","quantity":$quantity,
           "disposalItemId":$disposalItemId}"""

    private fun post(
        requestBody: String,
        key: String? = "wms-disposal-item-5",
        token: String? = token(),
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/disposals")
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
        fun `service 토큰으로 폐기하면 재고가 줄고 이력이 남으며 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                post(body())
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.quantityChange")
                    .isEqualTo(-4)
                    .jsonPath("$.quantityAfter")
                    .isEqualTo(96)

                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'DEFECTIVE'")).isEqualTo("96")
                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'NORMAL'")).isEqualTo("50")
                assertThat(scalar("SELECT requester_service FROM inventory_history")).isEqualTo(wmsPrincipalId.toString())
                assertThat(scalar("SELECT reference_type FROM inventory_history")).isEqualTo("DISPOSAL_ITEM")
                assertThat(scalar("SELECT reference_id FROM inventory_history")).isEqualTo("5")
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 다시 차감하지 않는다`() =
            runBlocking<Unit> {
                val first: String = postForBody(body(), "wms-disposal-item-5")
                postForBody(body(quantity = 3, disposalItemId = 6L), "wms-disposal-item-6")

                val replay: String = postForBody(body(), "wms-disposal-item-5")

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'DEFECTIVE'")).isEqualTo("93")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("2")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 한 번만 차감하고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { postForBody(body(), "same-key") } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'DEFECTIVE'")).isEqualTo("96")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("1")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `정상 품질 재고의 폐기는 400 INV_INVALID_DISPOSAL_QUALITY_STATUS이고 재고는 그대로다`() =
            runBlocking<Unit> {
                post(body(quality = "NORMAL"))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_DISPOSAL_QUALITY_STATUS")

                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'NORMAL'")).isEqualTo("50")
            }

        @Test
        fun `등록되지 않은 Lot은 404이고 재고 행이 없는 품질 상태도 404이다`() =
            runBlocking<Unit> {
                post(body(lot = "NOPE"))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_NOT_FOUND")
                post(body(quality = "DISPOSAL_SCHEDULED"))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVENTORY_NOT_FOUND")
            }

        @Test
        fun `가용 수량을 넘으면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY이고 같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                post(body(quantity = 101))
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")

                postForBody(body(), "k-1")
                post(body(quantity = 5), "k-1")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `수량 0과 멱등 키 형식 오류는 400이다`() =
            runBlocking<Unit> {
                post(body(quantity = 0)).expectStatus().isBadRequest
                post(body(), key = "한글 키").expectStatus().isBadRequest
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `admin은 403이고 토큰이 없으면 401이다`() =
            runBlocking<Unit> {
                post(body(), token = token("admin")).expectStatus().isForbidden
                post(body(), token = null).expectStatus().isUnauthorized

                assertThat(scalar("SELECT quantity FROM inventory WHERE quality_status = 'DEFECTIVE'")).isEqualTo("100")
            }
    }
}
