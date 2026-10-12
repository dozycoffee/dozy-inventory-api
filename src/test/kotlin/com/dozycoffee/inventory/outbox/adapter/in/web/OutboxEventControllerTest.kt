package com.dozycoffee.inventory.outbox.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.outbox.application.port.`in`.GetDeadOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.RequeueOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException
import com.dozycoffee.inventory.outbox.domain.exception.OutboxEventNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.LocalDateTime

@WebFluxTest(OutboxEventController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class OutboxEventControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var getDeadOutboxEventsUseCase: GetDeadOutboxEventsUseCase

    @MockitoBean
    private lateinit var requeueOutboxEventUseCase: RequeueOutboxEventUseCase

    private lateinit var webTestClient: WebTestClient

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 12, 10, 0)

    private val base: String = "/api/v1/outbox-events"

    private fun dead(): OutboxEventResult =
        OutboxEventResult(
            1L,
            AggregateType.INVENTORY,
            10L,
            "INVENTORY_INCREASED",
            "10:100",
            """{"inventoryId":10}""",
            OutboxStatus.DEAD,
            20,
            "RecordTooLargeException: too big",
            at.minusHours(1),
            at,
        )

    private fun requeued(): OutboxEventResult =
        dead().copy(status = OutboxStatus.PENDING, attemptCount = 0, lastError = null, deadAt = null)

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun call(
        method: HttpMethod,
        role: String?,
        uri: String,
        vararg variables: Any,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .method(method)
            .uri(uri, *variables)
            .headers { headers: HttpHeaders -> role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) } }
            .exchange()

    @Nested
    inner class `조회` {
        @Test
        fun `격리된 이벤트 목록을 페이지로 응답한다`() =
            runBlocking<Unit> {
                whenever(getDeadOutboxEventsUseCase.search(2, 5)).thenReturn(PageResult(listOf(dead()), 2, 5, 11L))

                call(HttpMethod.GET, "admin", "$base?page=2&size=5")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items[0].outboxEventId")
                    .isEqualTo(1)
                    .jsonPath("$.items[0].status")
                    .isEqualTo("DEAD")
                    .jsonPath("$.items[0].lastError")
                    .isEqualTo("RecordTooLargeException: too big")
                    .jsonPath("$.totalPages")
                    .isEqualTo(3)
                    .consumeWith(OutboxEventApiDocs.search())
            }

        @Test
        fun `생략하면 첫 페이지 20건이다`() =
            runBlocking<Unit> {
                whenever(getDeadOutboxEventsUseCase.search(0, 20)).thenReturn(PageResult(emptyList(), 0, 20, 0L))

                call(HttpMethod.GET, "admin", base)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.totalPages")
                    .isEqualTo(0)
            }

        @Test
        fun `상세를 응답하고 없는 이벤트는 404다`() =
            runBlocking<Unit> {
                whenever(getDeadOutboxEventsUseCase.getById(1L)).thenReturn(dead())
                whenever(getDeadOutboxEventsUseCase.getById(2L)).thenThrow(OutboxEventNotFoundException())

                call(HttpMethod.GET, "admin", "$base/{outboxEventId}", 1L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.payload")
                    .isEqualTo("""{"inventoryId":10}""")
                    .jsonPath("$.attemptCount")
                    .isEqualTo(20)
                    .consumeWith(OutboxEventApiDocs.detail())
                call(HttpMethod.GET, "admin", "$base/{outboxEventId}", 2L)
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_OUTBOX_EVENT_NOT_FOUND")
            }

        @Test
        fun `조건이 올바르지 않으면 400이다`() {
            call(HttpMethod.GET, "admin", "$base?page=abc").expectStatus().isBadRequest
            call(HttpMethod.GET, "admin", "$base/abc").expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `재발행` {
        @Test
        fun `발행 대기로 되돌린 이벤트를 응답한다`() =
            runBlocking<Unit> {
                whenever(requeueOutboxEventUseCase.requeue(1L)).thenReturn(requeued())

                call(HttpMethod.POST, "admin", "$base/{outboxEventId}/requeue", 1L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("PENDING")
                    .jsonPath("$.attemptCount")
                    .isEqualTo(0)
                    .jsonPath("$.lastError")
                    .isEmpty
                    .consumeWith(OutboxEventApiDocs.requeue())
            }

        @Test
        fun `서비스 오류는 오류 코드로 응답한다`() =
            runBlocking<Unit> {
                whenever(
                    requeueOutboxEventUseCase.requeue(any()),
                ).thenThrow(OutboxEventNotFoundException()).thenThrow(InvalidOutboxStateException())

                call(HttpMethod.POST, "admin", "$base/{outboxEventId}/requeue", 1L)
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_OUTBOX_EVENT_NOT_FOUND")
                    .consumeWith(OutboxEventApiDocs.notFound())
                call(HttpMethod.POST, "admin", "$base/{outboxEventId}/requeue", 1L)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_OUTBOX_STATE")
                    .consumeWith(OutboxEventApiDocs.conflict())
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `admin이 아니면 403이고 토큰이 없으면 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                listOf(HttpMethod.GET to base, HttpMethod.GET to "$base/1", HttpMethod.POST to "$base/1/requeue").forEach { (method, uri) ->
                    call(method, "service", uri).expectStatus().isForbidden
                    call(method, "warehouse_manager", uri).expectStatus().isForbidden
                    call(method, null, uri).expectStatus().isUnauthorized
                }

                verifyBlocking(getDeadOutboxEventsUseCase, never()) { search(any(), any()) }
                verifyBlocking(getDeadOutboxEventsUseCase, never()) { getById(any()) }
                verifyBlocking(requeueOutboxEventUseCase, never()) { requeue(any()) }
            }
    }
}
