package com.dozycoffee.inventory.reservation.adapter.`in`.web

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
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 예약 생성 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class ReservationApiIntegrationTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    private val webTestClient: WebTestClient by lazy { baseClient.mutate().responseTimeout(Duration.ofSeconds(30)).build() }

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var early: Long = 0L
    private var late: Long = 0L
    private val omsPrincipalId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b74")

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            early =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "EARLY", LocalDate.of(2027, 1, 1)).lotId),
                    quantity = 4,
                )
            late =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "LATE", LocalDate.of(2027, 6, 1)).lotId),
                    quantity = 10,
                )
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun serviceToken(): String = tokens.issue(type = PrincipalType.SYSTEM, id = omsPrincipalId, roles = listOf("inventory:service"))

    private fun body(
        quantity: Int = 6,
        orderId: String = "ORDER-1",
        product: Long = productId,
        expiresIn: Duration = Duration.ofMinutes(30),
    ): String {
        // 서비스의 시간대(Asia/Seoul) 오프셋을 붙인 시각으로 보낸다
        val expiresAt: OffsetDateTime = OffsetDateTime.now(clock).plus(expiresIn)
        return """{"warehouseId":10,"channel":"OMS","externalOrderId":"$orderId","expiresAt":"$expiresAt",
           "items":[{"productId":$product,"quantity":$quantity}]}"""
    }

    private fun post(
        requestBody: String,
        key: String? = "svc-oms-order-1",
        token: String? = serviceToken(),
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/reservations")
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
        fun `service 토큰으로 예약하면 유통기한 순으로 할당되고 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                post(body())
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("RESERVED")
                    .jsonPath("$.items[0].allocations.length()")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].allocations[0].inventoryId")
                    .isEqualTo(early)
                    .jsonPath("$.items[0].allocations[0].quantity")
                    .isEqualTo(4)
                    .jsonPath("$.items[0].allocations[1].quantity")
                    .isEqualTo(2)

                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("6")
                assertThat(scalar("SELECT requester_service FROM reservation")).isEqualTo(omsPrincipalId.toString())
                assertThat(scalar("SELECT created_by FROM reservation")).isEqualTo(omsPrincipalId.toString())
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'CREATED'")).isEqualTo("1")
            }

        @Test
        fun `응답의 만료 시각은 요청한 순간과 같고 서비스 시간대 오프셋을 가진다`() =
            runBlocking<Unit> {
                val response: String = postForBody(body(), "k-1")

                assertThat(response).containsPattern(""""expiresAt":"[^"]+\+09:00"""")
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 수량을 다시 잡지 않는다`() =
            runBlocking<Unit> {
                val requestBody: String = body()
                val first: String = postForBody(requestBody, "svc-oms-order-1")

                val replay: String = postForBody(requestBody, "svc-oms-order-1")

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("6")
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo("1")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 예약은 하나이고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val requestBody: String = body()

                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { postForBody(requestBody, "same-key") } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("6")
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo("1")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `가용 수량이 모자라면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY이고 아무것도 잡지 않는다`() =
            runBlocking<Unit> {
                post(body(quantity = 15))
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")

                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("0")
                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo("0")
            }

        @Test
        fun `같은 주문에 다른 멱등 키로 다시 예약하면 409 INV_DUPLICATE_ORDER_RESERVATION`() =
            runBlocking<Unit> {
                postForBody(body(quantity = 3), "k-1")

                post(body(quantity = 3), "k-2")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_DUPLICATE_ORDER_RESERVATION")

                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("3")
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                postForBody(body(), "k-1")

                post(body(quantity = 7), "k-1")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `없는 상품은 404이고 비활성 상품은 409 INV_PRODUCT_NOT_RESERVABLE`() =
            runBlocking<Unit> {
                post(body(product = 999_999L))
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")

                databaseClient
                    .sql("UPDATE product SET product_status = 'INACTIVE'")
                    .fetch()
                    .rowsUpdated()
                    .awaitFirst()
                post(body(), "k-2")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_RESERVABLE")
            }

        @Test
        fun `채널 상한 1시간을 넘는 만료 시각은 400 INV_INVALID_RESERVATION_EXPIRY`() =
            runBlocking<Unit> {
                post(body(expiresIn = Duration.ofHours(2)))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_EXPIRY")
                post(body(expiresIn = Duration.ofMinutes(-1)), "k-2")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_EXPIRY")

                assertThat(scalar("SELECT COUNT(*) FROM reservation")).isEqualTo("0")
            }

        @Test
        fun `멱등 키 형식 오류는 400이고 service가 아닌 토큰은 403, 토큰이 없으면 401이다`() {
            post(body(), "key with space").expectStatus().isBadRequest
            post(body(), token = tokens.issue(roles = listOf("inventory:admin"))).expectStatus().isForbidden
            post(body(), token = null).expectStatus().isUnauthorized
        }
    }
}
