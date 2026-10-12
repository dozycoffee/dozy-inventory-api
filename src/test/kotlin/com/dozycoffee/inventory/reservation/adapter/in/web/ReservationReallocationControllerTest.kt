package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.reservation.application.port.`in`.ReallocateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult
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
import java.time.LocalDate

@WebFluxTest(ReservationReallocationController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class ReservationReallocationControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var reallocateReservationUseCase: ReallocateReservationUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: ReallocationResult =
        ReallocationResult(
            1L,
            ReservationStatus.CONFIRMED,
            listOf(5L),
            listOf(
                ReallocationResult.Item(
                    100L,
                    listOf(
                        ReallocationResult.Allocation(5L, 50L, "LOT-A", LocalDate.of(2027, 1, 1), 4),
                        ReallocationResult.Allocation(11L, 51L, "LOT-B", null, 2),
                    ),
                ),
            ),
        )

    private val body: String = """{"shortages":[{"inventoryId":5,"shortageQuantity":2}]}"""

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "wms-realloc-1",
        reservationId: Long = 1L,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/reservations/{reservationId}/reallocation", reservationId)
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `재할당` {
        @Test
        fun `service role이 호출하면 200과 재할당 뒤의 할당을 응답한다`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("CONFIRMED")
                    .jsonPath("$.heldInventoryIds[0]")
                    .isEqualTo(5)
                    .jsonPath("$.items[0].allocations.length()")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].allocations[0].lotNumber")
                    .isEqualTo("LOT-A")
                    .jsonPath("$.items[0].allocations[0].expirationDate")
                    .isEqualTo("2027-01-01")
                    .jsonPath("$.items[0].allocations[1].expirationDate")
                    .isEmpty
                    .consumeWith(ReservationReallocationApiDocs.reallocate())
            }

        @Test
        fun `경로의 예약 ID와 본문과 멱등 키 헤더를 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenReturn(result)

                post("service", reservationId = 42L).expectStatus().isOk

                val command = argumentCaptor<ReallocateReservationCommand>()
                verifyBlocking(reallocateReservationUseCase) { reallocate(command.capture()) }
                assertEquals(42L, command.firstValue.reservationId)
                assertEquals(listOf(ReallocateReservationCommand.Shortage(5L, 2)), command.firstValue.shortages)
                assertEquals("wms-realloc-1", command.firstValue.idempotencyKey)
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

                verifyBlocking(reallocateReservationUseCase, never()) { reallocate(any()) }
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
            post("service", """{"shortages":[{"inventoryId":0,"shortageQuantity":0}]}""")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(2)
                .consumeWith(ReservationReallocationApiDocs.invalid())
        }

        @Test
        fun `목록이 없거나 비어 있거나 1001개이면 400이고 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                post("service", """{}""").expectStatus().isBadRequest
                post("service", """{"shortages":[]}""").expectStatus().isBadRequest
                val tooMany: String = (1..1001).joinToString(",") { """{"inventoryId":$it,"shortageQuantity":1}""" }
                post("service", """{"shortages":[$tooMany]}""").expectStatus().isBadRequest

                verifyBlocking(reallocateReservationUseCase, never()) { reallocate(any()) }
            }

        @Test
        fun `같은 재고 행을 두 번 담으면 400 INV_INVALID_REALLOCATION`() {
            post("service", """{"shortages":[{"inventoryId":5,"shortageQuantity":1},{"inventoryId":5,"shortageQuantity":2}]}""")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_REALLOCATION")
        }

        @Test
        fun `예약과 맞지 않는 부족 수량은 400 INV_INVALID_REALLOCATION`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any()))
                    .thenThrow(InvalidDomainValueException(ReservationErrorCode.INVALID_REALLOCATION))

                post("service")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_REALLOCATION")
            }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `예약이 없으면 404 INV_RESERVATION_NOT_FOUND`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenThrow(ReservationNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_NOT_FOUND")
                    .consumeWith(ReservationReallocationApiDocs.notFound())
            }

        @Test
        fun `확정이 아닌 예약은 409 INV_INVALID_RESERVATION_STATE`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenThrow(InvalidReservationStateException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_STATE")
                    .consumeWith(ReservationReallocationApiDocs.conflict())
            }

        @Test
        fun `대체 재고가 모자라면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenThrow(InsufficientAvailableQuantityException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")
            }

        @Test
        fun `다른 내용의 같은 키는 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(reallocateReservationUseCase.reallocate(any())).thenThrow(IdempotencyKeyConflictException())

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
                whenever(reallocateReservationUseCase.reallocate(any())).thenThrow(ReservationChangedConcurrentlyException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_CHANGED_CONCURRENTLY")
            }
    }
}
