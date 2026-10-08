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

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 출고 확정 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class ReservationFulfillmentApiIntegrationTest {
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
    private val wmsPrincipalId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
    private val mapper: JsonMapper = JsonMapper.builder().build()

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

    private fun token(role: String = "inventory:service"): String =
        tokens.issue(type = PrincipalType.SYSTEM, id = wmsPrincipalId, roles = listOf(role))

    private fun call(
        uri: String,
        requestBody: String?,
        key: String? = null,
        token: String? = token(),
    ): WebTestClient.ResponseSpec {
        val request: WebTestClient.RequestBodySpec =
            webTestClient.post().uri(uri).headers { headers ->
                token?.let { headers.setBearerAuth(it) }
                key?.let { headers.set("Idempotency-Key", it) }
            }
        return (requestBody?.let { request.contentType(MediaType.APPLICATION_JSON).bodyValue(it) } ?: request).exchange()
    }

    /** 6개를 예약해 early 4개, late 2개로 할당하고 확정한 예약의 ID */
    private fun confirmedReservation(orderId: String = "ORDER-1"): Long {
        val expiresAt: OffsetDateTime = OffsetDateTime.now(clock).plusMinutes(30)
        val created: JsonNode =
            mapper.readTree(
                call(
                    "/api/v1/reservations",
                    """{"warehouseId":10,"channel":"OMS","externalOrderId":"$orderId","expiresAt":"$expiresAt","items":[{"productId":$productId,"quantity":6}]}""",
                    key = "key-$orderId",
                ).expectStatus()
                    .isOk
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody,
            )
        val id: Long = created["reservationId"].asLong()
        call("/api/v1/reservations/$id/confirm", null).expectStatus().isOk
        return id
    }

    private fun body(
        earlyShipped: Int,
        lateShipped: Int,
    ): String =
        """{"allocations":[{"inventoryId":$early,"shippedQuantity":$earlyShipped},{"inventoryId":$late,"shippedQuantity":$lateShipped}]}"""

    private fun fulfill(
        id: Long,
        requestBody: String,
        key: String? = "wms-outbound-1",
        token: String? = token(),
    ): WebTestClient.ResponseSpec = call("/api/v1/reservations/$id/fulfillment", requestBody, key, token)

    private fun fulfillForBody(
        id: Long,
        requestBody: String,
        key: String = "wms-outbound-1",
    ): String =
        fulfill(id, requestBody, key)
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
        fun `전량 출고하면 FULFILLED가 되고 처리 후 수량을 응답하며 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                fulfill(id, body(4, 2))
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("FULFILLED")
                    .jsonPath("$.allocations[0].inventoryId")
                    .isEqualTo(early)
                    .jsonPath("$.allocations[0].quantityAfter")
                    .isEqualTo(0)
                    .jsonPath("$.allocations[1].quantityAfter")
                    .isEqualTo(8)

                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("8")
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("0")
                assertThat(scalar("SELECT status FROM reservation")).isEqualTo("FULFILLED")
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE requester_service = '$wmsPrincipalId' AND history_type = 'OUTBOUND'",
                    ),
                ).isEqualTo("2")
                assertThat(scalar("SELECT updated_by FROM reservation")).isEqualTo(wmsPrincipalId.toString())
            }

        @Test
        fun `결품은 예약 수량만 되돌리고 총 수량은 그대로이며 처리 후 수량은 null이다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                fulfill(id, body(3, 0))
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.allocations[0].shortageQuantity")
                    .isEqualTo(1)
                    .jsonPath("$.allocations[1].shortageQuantity")
                    .isEqualTo(2)
                    .jsonPath("$.allocations[1].quantityAfter")
                    .isEmpty

                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("11")
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("0")
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 수량을 다시 줄이지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()
                val first: String = fulfillForBody(id, body(3, 1))

                val replay: String = fulfillForBody(id, body(3, 1))

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("10")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("2")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 한 번만 반영하고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { fulfillForBody(id, body(3, 1)) } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("10")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("2")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `다른 키로 재요청하면 409 INV_IDEMPOTENCY_KEY_CONFLICT이고 다른 수량은 409 INV_INVALID_RESERVATION_STATE이다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()
                fulfillForBody(id, body(3, 1))

                fulfill(
                    id,
                    body(3, 1),
                    key = "other-key",
                ).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
                fulfill(
                    id,
                    body(4, 2),
                ).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INV_INVALID_RESERVATION_STATE")
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("10")
            }

        @Test
        fun `할당과 맞지 않는 출고 수량은 400 INV_INVALID_FULFILLMENT이고 아무것도 바뀌지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                fulfill(id, body(5, 1))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_FULFILLMENT")
                fulfill(id, """{"allocations":[{"inventoryId":$early,"shippedQuantity":4}]}""", key = "k-2")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_FULFILLMENT")

                assertThat(scalar("SELECT status FROM reservation")).isEqualTo("CONFIRMED")
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("14")
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo("0")
            }

        @Test
        fun `확정 전이거나 해제된 예약은 409이고 없는 예약은 404이다`() =
            runBlocking<Unit> {
                val expiresAt: OffsetDateTime = OffsetDateTime.now(clock).plusMinutes(30)
                val reserved: Long =
                    mapper
                        .readTree(
                            call(
                                "/api/v1/reservations",
                                """{"warehouseId":10,"channel":"OMS","externalOrderId":"O-1","expiresAt":"$expiresAt","items":[{"productId":$productId,"quantity":6}]}""",
                                key = "k-1",
                            ).expectStatus()
                                .isOk
                                .expectBody(String::class.java)
                                .returnResult()
                                .responseBody,
                        )["reservationId"]
                        .asLong()

                fulfill(
                    reserved,
                    body(4, 2),
                ).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INV_INVALID_RESERVATION_STATE")
                call("/api/v1/reservations/$reserved/confirm", null).expectStatus().isOk
                call("/api/v1/reservations/$reserved/release", null).expectStatus().isOk
                fulfill(reserved, body(4, 2), key = "k-2").expectStatus().isEqualTo(409)
                fulfill(
                    999_999L,
                    body(4, 2),
                ).expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("INV_RESERVATION_NOT_FOUND")
                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("14")
            }

        @Test
        fun `멱등 키 형식 오류와 필드 검증 오류는 400이고 service가 아닌 토큰은 403, 토큰이 없으면 401이다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                fulfill(id, body(4, 2), key = "key with space").expectStatus().isBadRequest
                fulfill(id, """{"allocations":[]}""").expectStatus().isBadRequest
                fulfill(id, """{"allocations":[{"inventoryId":0,"shippedQuantity":-1}]}""").expectStatus().isBadRequest
                fulfill(id, body(4, 2), token = token("inventory:admin")).expectStatus().isForbidden
                fulfill(id, body(4, 2), token = null).expectStatus().isUnauthorized
                assertThat(scalar("SELECT status FROM reservation")).isEqualTo("CONFIRMED")
            }
    }
}
