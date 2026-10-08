package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(ReservationFulfillmentController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class ReservationFulfillmentControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var fulfillReservationUseCase: FulfillReservationUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: FulfillmentResult =
        FulfillmentResult(
            1L,
            ReservationStatus.FULFILLED,
            listOf(
                FulfillmentResult.Allocation(100L, 5L, 6, 4, 2, 94),
                FulfillmentResult.Allocation(100L, 7L, 4, 0, 4, null),
            ),
        )

    private val body: String = """{"allocations":[{"inventoryId":5,"shippedQuantity":4},{"inventoryId":7,"shippedQuantity":0}]}"""

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "wms-outbound-1",
        reservationId: Long = 1L,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/reservations/{reservationId}/fulfillment", reservationId)
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `출고 확정` {
        @Test
        fun `service role이 호출하면 200과 재고 행별 출고 결과를 응답한다`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("FULFILLED")
                    .jsonPath("$.allocations.length()")
                    .isEqualTo(2)
                    .jsonPath("$.allocations[0].quantityAfter")
                    .isEqualTo(94)
                    .jsonPath("$.allocations[0].shortageQuantity")
                    .isEqualTo(2)
                    .jsonPath("$.allocations[1].quantityAfter")
                    .isEmpty
                    .consumeWith(ReservationFulfillmentApiDocs.fulfill())
            }

        @Test
        fun `경로의 예약 ID와 본문과 멱등 키 헤더와 요청 주체를 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenReturn(result)

                post("service", reservationId = 42L).expectStatus().isOk

                val command = argumentCaptor<FulfillReservationCommand>()
                verifyBlocking(fulfillReservationUseCase) { fulfill(command.capture()) }
                val actual: FulfillReservationCommand = command.firstValue
                assertEquals(42L, actual.reservationId)
                assertEquals(
                    listOf(FulfillReservationCommand.Allocation(5L, 4), FulfillReservationCommand.Allocation(7L, 0)),
                    actual.allocations,
                )
                assertEquals("wms-outbound-1", actual.idempotencyKey)
                assertEquals("00000000-0000-7000-8000-000000000001", actual.requesterService)
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `service가 아닌 role은 403이고 토큰이 없으면 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                post("warehouse_manager").expectStatus().isForbidden
                post("admin").expectStatus().isForbidden
                post(null).expectStatus().isUnauthorized

                verifyBlocking(fulfillReservationUseCase, never()) { fulfill(any()) }
            }
    }

    @Nested
    inner class `요청 검증` {
        @Test
        fun `멱등 키 헤더가 없으면 400`() {
            post("service", key = null)
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
        }

        @Test
        fun `본문 필드가 잘못되면 400 VALIDATION_FAILED와 필드별 errors를 응답한다`() {
            post("service", """{"allocations":[{"inventoryId":0,"shippedQuantity":-1}]}""")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(2)
                .consumeWith(ReservationFulfillmentApiDocs.invalid())
        }

        @Test
        fun `목록이 없거나 비어 있거나 1001개이면 400이고 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                post("service", """{}""").expectStatus().isBadRequest
                post("service", """{"allocations":[]}""").expectStatus().isBadRequest
                val tooMany: String = (1..1001).joinToString(",") { """{"inventoryId":$it,"shippedQuantity":1}""" }
                post("service", """{"allocations":[$tooMany]}""").expectStatus().isBadRequest

                verifyBlocking(fulfillReservationUseCase, never()) { fulfill(any()) }
            }

        @Test
        fun `같은 재고 행을 두 번 담으면 400 INV_INVALID_FULFILLMENT`() {
            post("service", """{"allocations":[{"inventoryId":5,"shippedQuantity":1},{"inventoryId":5,"shippedQuantity":2}]}""")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_FULFILLMENT")
        }

        @Test
        fun `할당과 맞지 않는 출고 수량은 400 INV_INVALID_FULFILLMENT`() =
            runBlocking<Unit> {
                whenever(
                    fulfillReservationUseCase.fulfill(any()),
                ).thenThrow(InvalidDomainValueException(ReservationErrorCode.INVALID_FULFILLMENT))

                post("service")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_FULFILLMENT")
                    .consumeWith(ReservationFulfillmentApiDocs.invalidFulfillment())
            }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `예약이 없으면 404 INV_RESERVATION_NOT_FOUND`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenThrow(ReservationNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_NOT_FOUND")
                    .consumeWith(ReservationFulfillmentApiDocs.notFound())
            }

        @Test
        fun `불가능한 전이는 409 INV_INVALID_RESERVATION_STATE`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenThrow(InvalidReservationStateException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_STATE")
                    .consumeWith(ReservationFulfillmentApiDocs.conflict())
            }

        @Test
        fun `다른 키의 재요청은 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenThrow(IdempotencyKeyConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }

        @Test
        fun `계속 겹쳐 처리하지 못하면 409 INV_RESERVATION_CHANGED_CONCURRENTLY`() =
            runBlocking<Unit> {
                whenever(fulfillReservationUseCase.fulfill(any())).thenThrow(ReservationChangedConcurrentlyException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_CHANGED_CONCURRENTLY")
            }
    }
}
