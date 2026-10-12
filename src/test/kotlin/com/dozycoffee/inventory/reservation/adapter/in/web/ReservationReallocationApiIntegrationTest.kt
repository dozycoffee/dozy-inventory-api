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

/** `local` 프로필 없이 실제 보안 체인, 서비스, MySQL을 모두 거쳐 Lot 재할당 API를 끝까지 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class ReservationReallocationApiIntegrationTest {
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

    private fun reallocate(
        id: Long,
        inventoryId: Long,
        quantity: Int,
        key: String? = "wms-realloc-1",
        token: String? = token(),
    ): WebTestClient.ResponseSpec =
        call(
            "/api/v1/reservations/$id/reallocation",
            """{"shortages":[{"inventoryId":$inventoryId,"shortageQuantity":$quantity}]}""",
            key,
            token,
        )

    private fun reallocateForBody(
        id: Long,
        inventoryId: Long,
        quantity: Int,
    ): String =
        reallocate(id, inventoryId, quantity)
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
        fun `부족 수량을 다른 Lot으로 옮기고 응답하며 부족 행은 보류되고 요청 주체는 토큰의 principalId다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                reallocate(id, early, 3)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("CONFIRMED")
                    .jsonPath("$.heldInventoryIds[0]")
                    .isEqualTo(early)
                    .jsonPath("$.items[0].allocations[0].inventoryId")
                    .isEqualTo(early)
                    .jsonPath("$.items[0].allocations[0].quantity")
                    .isEqualTo(1)
                    .jsonPath("$.items[0].allocations[1].lotNumber")
                    .isEqualTo("LATE")
                    .jsonPath("$.items[0].allocations[1].quantity")
                    .isEqualTo(5)

                assertThat(scalar("SELECT SUM(quantity) FROM inventory")).isEqualTo("14")
                assertThat(scalar("SELECT SUM(reserved_quantity) FROM inventory")).isEqualTo("6")
                assertThat(scalar("SELECT CAST(allocation_hold AS UNSIGNED) FROM inventory WHERE inventory_id = $early")).isEqualTo("1")
                assertThat(
                    scalar("SELECT created_by FROM reservation_event WHERE event_type = 'REALLOCATED'"),
                ).isEqualTo(wmsPrincipalId.toString())
            }

        @Test
        fun `같은 멱등 키의 재요청은 처음 응답과 똑같은 본문을 반환하고 다시 옮기지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()
                val first: String = reallocateForBody(id, early, 3)

                val replay: String = reallocateForBody(id, early, 3)

                assertThat(replay).isEqualTo(first)
                assertThat(scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $late")).isEqualTo("5")
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'REALLOCATED'")).isEqualTo("1")
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 요청돼도 한 번만 반영하고 모두 같은 본문을 받는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                val bodies: List<String> =
                    // 블로킹 호출이라 Default 풀을 점유하지 않도록 IO 디스패처를 쓴다
                    (1..20).map { async(Dispatchers.IO) { reallocateForBody(id, early, 3) } }.awaitAll()

                assertThat(bodies.toSet()).hasSize(1)
                assertThat(scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $late")).isEqualTo("5")
                assertThat(scalar("SELECT COUNT(*) FROM reservation_event WHERE event_type = 'REALLOCATED'")).isEqualTo("1")
            }
    }

    @Nested
    inner class `오류 응답` {
        @Test
        fun `같은 키에 다른 수량이면 409 INV_IDEMPOTENCY_KEY_CONFLICT이다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()
                reallocateForBody(id, early, 3)

                reallocate(
                    id,
                    early,
                    1,
                ).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `예약에 없는 재고 행이거나 수량이 할당을 넘으면 400 INV_INVALID_REALLOCATION이고 보류하지 않는다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                reallocate(id, 99_999L, 1)
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_REALLOCATION")
                reallocate(id, early, 5)
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_REALLOCATION")

                assertThat(scalar("SELECT CAST(allocation_hold AS UNSIGNED) FROM inventory WHERE inventory_id = $early")).isEqualTo("0")
            }

        @Test
        fun `예약이 없으면 404이고 확정 전 예약은 409이다`() =
            runBlocking<Unit> {
                reallocate(
                    999_999L,
                    early,
                    1,
                ).expectStatus().isNotFound.expectBody().jsonPath("$.code").isEqualTo("INV_RESERVATION_NOT_FOUND")
                val expiresAt: OffsetDateTime = OffsetDateTime.now(clock).plusMinutes(30)
                val created: JsonNode =
                    mapper.readTree(
                        call(
                            "/api/v1/reservations",
                            """{"warehouseId":10,"channel":"OMS","externalOrderId":"O-2","expiresAt":"$expiresAt","items":[{"productId":$productId,"quantity":2}]}""",
                            key = "key-O-2",
                        ).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody,
                    )

                reallocate(created["reservationId"].asLong(), early, 1)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_STATE")
            }

        @Test
        fun `대체 재고가 모자라면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY이지만 부족 행은 보류된다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()
                confirmedReservation("ORDER-2")
                reallocate(
                    id,
                    early,
                    4,
                ).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")

                assertThat(scalar("SELECT CAST(allocation_hold AS UNSIGNED) FROM inventory WHERE inventory_id = $early")).isEqualTo("1")
                assertThat(scalar("SELECT reserved_quantity FROM inventory WHERE inventory_id = $early")).isEqualTo("4")
            }

        @Test
        fun `service가 아닌 role은 403이고 토큰이 없으면 401이며 멱등 키가 없으면 400이다`() =
            runBlocking<Unit> {
                val id: Long = confirmedReservation()

                reallocate(id, early, 1, token = token("inventory:admin")).expectStatus().isForbidden
                reallocate(id, early, 1, token = null).expectStatus().isUnauthorized
                reallocate(id, early, 1, key = null).expectStatus().isBadRequest

                assertThat(scalar("SELECT CAST(allocation_hold AS UNSIGNED) FROM inventory WHERE inventory_id = $early")).isEqualTo("0")
            }
    }
}
