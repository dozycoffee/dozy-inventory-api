package com.dozycoffee.inventory.outbox.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient

/** 실제 보안 체인, 서비스, MySQL을 거쳐 요청의 추적 컨텍스트가 Outbox 이벤트에 함께 저장되는지 검증한다(ADR-0038) */
@SpringBootTest
@AutoConfigureWebTestClient
class OutboxTraceparentIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture

    @BeforeEach
    fun setUp() = runBlocking<Unit> { InventoryDbFixture(databaseClient, lotPersistenceAdapter).also { fixture = it }.cleanUp() }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun register(
        code: String,
        traceparent: String?,
    ) {
        webTestClient
            .post()
            .uri("/api/v1/products")
            .headers {
                it.setBearerAuth(tokens.issue(roles = listOf("inventory:admin")))
                traceparent?.let { value -> it.set("traceparent", value) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"productCode":"$code","productName":"상품","category":"BEAN","unit":"KG","shelfLifeDays":180}""")
            .exchange()
            .expectStatus()
            .isCreated
    }

    private suspend fun rows(): List<Map<String, Any?>> =
        databaseClient
            .sql("SELECT event_id, event_version, traceparent FROM outbox_event ORDER BY outbox_event_id")
            .fetch()
            .all()
            .collectList()
            .awaitFirst()

    @Test
    fun `요청의 traceparent의 trace-id를 이어받아 이벤트와 함께 저장한다`() =
        runBlocking<Unit> {
            register("TRACE-1", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")

            val row: Map<String, Any?> = rows().single()
            assertThat(row["traceparent"].toString()).matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01")
            assertThat(row["traceparent"].toString()).doesNotContain("00f067aa0ba902b7")
            assertThat(row["event_id"].toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
            assertThat(row["event_version"].toString()).isEqualTo("1")
        }

    @Test
    fun `traceparent가 없는 요청도 새 추적 컨텍스트를 저장하고 이벤트마다 ID가 다르다`() =
        runBlocking<Unit> {
            register("TRACE-2", null)
            register("TRACE-3", "garbage")

            val rows: List<Map<String, Any?>> = rows()
            assertThat(rows.map { it["traceparent"].toString() }).allMatch { it.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")) }
            assertThat(rows.map { it["event_id"].toString() }.toSet()).hasSize(2)
        }
}
