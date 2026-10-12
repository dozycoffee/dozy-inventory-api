package com.dozycoffee.inventory.outbox.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient

/** 실제 보안 체인, 서비스, MySQL을 거쳐 격리된 Outbox 이벤트의 조회와 재발행을 검증한다 */
@SpringBootTest
@AutoConfigureWebTestClient
class OutboxEventApiIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var deadId: Long = 0L
    private var pendingId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            deadId = insert("DEAD", "NOW(6)", "'boom'", 20)
            pendingId = insert("PENDING", "NULL", "NULL", 0)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun insert(
        status: String,
        deadAt: String,
        lastError: String,
        attempts: Int,
    ): Long {
        databaseClient
            .sql(
                "INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, partition_key, payload, status, attempt_count, " +
                    "last_error, dead_at, created_at) " +
                    "VALUES ('PRODUCT', 1, 'PRODUCT_REGISTERED', 'p:1', '{\"a\":1}', '$status', $attempts, " +
                    "$lastError, $deadAt, NOW(6))",
            ).fetch()
            .rowsUpdated()
            .awaitFirst()
        return databaseClient
            .sql("SELECT MAX(outbox_event_id) AS id FROM outbox_event")
            .fetch()
            .one()
            .awaitFirst()["id"]
            .toString()
            .toLong()
    }

    private fun admin(): (HttpHeaders) -> Unit = { it.setBearerAuth(tokens.issue(roles = listOf("inventory:admin"))) }

    @Test
    fun `격리된 이벤트만 목록에 나오고 상세는 격리 상태가 아니면 404다`() {
        webTestClient
            .get()
            .uri("/api/v1/outbox-events")
            .headers(admin())
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.items[0].outboxEventId")
            .isEqualTo(deadId)
            .jsonPath("$.items[0].lastError")
            .isEqualTo("boom")
        webTestClient
            .get()
            .uri("/api/v1/outbox-events/$deadId")
            .headers(admin())
            .exchange()
            .expectStatus()
            .isOk
        webTestClient
            .get()
            .uri("/api/v1/outbox-events/$pendingId")
            .headers(admin())
            .exchange()
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `재발행하면 발행 대기가 되고 한 번 더 요청하면 409다`() =
        runBlocking<Unit> {
            webTestClient
                .post()
                .uri("/api/v1/outbox-events/$deadId/requeue")
                .headers(admin())
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("PENDING")
                .jsonPath("$.attemptCount")
                .isEqualTo(0)
            webTestClient
                .post()
                .uri("/api/v1/outbox-events/$deadId/requeue")
                .headers(admin())
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_OUTBOX_STATE")
            webTestClient
                .post()
                .uri("/api/v1/outbox-events/999999/requeue")
                .headers(admin())
                .exchange()
                .expectStatus()
                .isNotFound
            webTestClient
                .get()
                .uri(
                    "/api/v1/outbox-events",
                ).headers(admin())
                .exchange()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0)
        }

    @Test
    fun `admin이 아니면 거부한다`() {
        webTestClient
            .get()
            .uri("/api/v1/outbox-events")
            .headers { it.setBearerAuth(tokens.issue(roles = listOf("inventory:warehouse_manager"))) }
            .exchange()
            .expectStatus()
            .isForbidden
        webTestClient
            .post()
            .uri("/api/v1/outbox-events/$deadId/requeue")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
