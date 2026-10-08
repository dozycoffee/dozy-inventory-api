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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 예약 확정·해제·연장 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class ReservationTransitionApiIntegrationTest {
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
    private val omsPrincipalId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b74")
    private val mapper: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            fixture.insertInventory(
                10L,
                productId,
                checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId),
                quantity = 10,
            )
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun serviceToken(): String = tokens.issue(type = PrincipalType.SYSTEM, id = omsPrincipalId, roles = listOf("inventory:service"))

    private fun createBody(
        quantity: Int = 6,
        orderId: String = "ORDER-1",
    ): String {
        val expiresAt: OffsetDateTime = OffsetDateTime.now(clock).plusMinutes(30)
        return """{"warehouseId":10,"channel":"OMS","externalOrderId":"$orderId","expiresAt":"$expiresAt",
           "items":[{"productId":$productId,"quantity":$quantity}]}"""
    }

    private fun create(
        requestBody: String = createBody(),
        key: String = "key-1",
    ): JsonNode =
        mapper.readTree(
            webTestClient
                .post()
                .uri("/api/v1/reservations")
                .headers { headers ->
                    headers.setBearerAuth(serviceToken())
                    headers.set("Idempotency-Key", key)
                }.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody,
        )

    private fun post(
        id: Long,
        action: String,
        requestBody: String? = null,
        token: String? = serviceToken(),
    ): WebTestClient.ResponseSpec {
        val request: WebTestClient.RequestBodySpec =
            webTestClient
                .post()
                .uri("/api/v1/reservations/{id}/$action", id)
                .headers { headers -> token?.let { headers.setBearerAuth(it) } }
        return (requestBody?.let { request.contentType(MediaType.APPLICATION_JSON).bodyValue(it) } ?: request).exchange()
    }

    private fun postForBody(
        id: Long,
        action: String,
        requestBody: String? = null,
    ): String =
        post(id, action, requestBody)
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
    inner class `확정` {
        @Test
        fun `확정하면 CONFIRMED가 되고 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()

                post(id, "confirm")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("CONFIRMED")
                    .jsonPath("$.expiresAt")
                    .isEmpty

                assertThat(scalar("SELECT status FROM reservation")).isEqualTo("CONFIRMED")
                assertThat(scalar("SELECT updated_by FROM reservation")).isEqualTo(omsPrincipalId.toString())
                assertThat(
                    scalar("SELECT created_by FROM reservation_event WHERE event_type = 'CONFIRMED'"),
                ).isEqualTo(omsPrincipalId.toString())
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("6")
            }

        @Test
        fun `다시 확정해도 같은 본문을 반환하고 이력은 한 번이다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()
                val first: String = postForBody(id, "confirm")

                val replay: String = postForBody(id, "confirm")

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'CONFIRMED'")).isEqualTo("1")
            }

        @Test
        fun `같은 예약을 20개가 동시에 확정해도 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()

                val bodies: List<String> = (1..20).map { async(Dispatchers.IO) { postForBody(id, "confirm") } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'CONFIRMED'")).isEqualTo("1")
            }
    }

    @Nested
    inner class `해제` {
        @Test
        fun `해제하면 예약 수량이 돌아오고 다시 해제해도 두 번 돌아오지 않는다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()
                create(createBody(quantity = 3, orderId = "ORDER-2"), "key-2")

                post(id, "release")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("RELEASED")
                post(id, "release")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("RELEASED")

                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("3")
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'RELEASED'")).isEqualTo("1")
            }

        @Test
        fun `해제된 예약은 확정할 수 없다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()
                post(id, "release").expectStatus().isOk

                post(id, "confirm")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_STATE")
            }
    }

    @Nested
    inner class `연장` {
        @Test
        fun `새 만료 시각으로 늘리고 최대 만료 시각을 넘으면 400이다`() =
            runBlocking<Unit> {
                val created: JsonNode = create()
                val id: Long = created["reservationId"].asLong()
                val newExpiresAt: OffsetDateTime = OffsetDateTime.parse(created["expiresAt"].asString()).plusMinutes(20)

                post(id, "extend", """{"expiresAt":"$newExpiresAt"}""")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.expiresAt")
                    .value<String> { assertThat(OffsetDateTime.parse(it).toInstant()).isEqualTo(newExpiresAt.toInstant()) }

                val tooLate: OffsetDateTime = OffsetDateTime.parse(created["maxExpiresAt"].asString()).plusMinutes(1)
                post(id, "extend", """{"expiresAt":"$tooLate"}""")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_EXPIRY")
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'EXTENDED'")).isEqualTo("1")
            }

        @Test
        fun `오프셋이 없는 만료 시각은 400이다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()

                post(id, "extend", """{"expiresAt":"2026-12-31T10:00:00"}""").expectStatus().isBadRequest
            }
    }

    @Nested
    inner class `오류와 권한` {
        @Test
        fun `없는 예약은 404 INV_RESERVATION_NOT_FOUND`() {
            post(999_999L, "confirm")
                .expectStatus()
                .isNotFound
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_RESERVATION_NOT_FOUND")
            post(999_999L, "release").expectStatus().isNotFound
            post(999_999L, "extend", """{"expiresAt":"2030-01-01T00:00:00Z"}""").expectStatus().isNotFound
        }

        @Test
        fun `만료 시각이 지난 예약은 확정할 수 없다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()
                databaseClient
                    .sql(
                        "UPDATE reservation SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)",
                    ).fetch()
                    .rowsUpdated()
                    .awaitFirst()

                post(id, "confirm")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_EXPIRED")
            }

        @Test
        fun `service가 아닌 토큰은 403이고 토큰이 없으면 401이다`() =
            runBlocking<Unit> {
                val id: Long = create()["reservationId"].asLong()
                val admin: String = tokens.issue(roles = listOf("inventory:admin"))

                listOf(
                    "confirm" to null,
                    "release" to null,
                    "extend" to """{"expiresAt":"2030-01-01T00:00:00Z"}""",
                ).forEach { (action, body) ->
                    post(id, action, body, admin).expectStatus().isForbidden
                    post(id, action, body, null).expectStatus().isUnauthorized
                }
                assertThat(scalar("SELECT status FROM reservation")).isEqualTo("RESERVED")
            }
    }
}
