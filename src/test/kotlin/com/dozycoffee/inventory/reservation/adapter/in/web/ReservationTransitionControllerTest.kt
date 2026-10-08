package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExtendReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.InvalidReservationStateException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import com.dozycoffee.inventory.reservation.domain.exception.ReservationExpiredException
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
import java.time.LocalDateTime

@WebFluxTest(ReservationTransitionController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class, ClockConfig::class)
@ExtendWith(RestDocumentationExtension::class)
class ReservationTransitionControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @MockitoBean
    private lateinit var releaseReservationUseCase: ReleaseReservationUseCase

    @MockitoBean
    private lateinit var extendReservationUseCase: ExtendReservationUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: ReservationResult =
        ReservationResult(
            1L,
            ReservationStatus.RESERVED,
            10L,
            "OMS",
            "ORDER-1",
            LocalDateTime.of(2026, 10, 7, 21, 30),
            LocalDateTime.of(2026, 10, 7, 22, 0),
            listOf(ReservationResult.Item(100L, 10, listOf(ReservationResult.Allocation(5L, 4), ReservationResult.Allocation(7L, 6)))),
        )

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        action: String,
        requestBody: String? = null,
        reservationId: Long = 1L,
    ): WebTestClient.ResponseSpec {
        val request: WebTestClient.RequestBodySpec =
            webTestClient
                .post()
                .uri("/api/v1/reservations/{reservationId}/$action", reservationId)
                .headers { headers: HttpHeaders -> role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) } }
        return (requestBody?.let { request.contentType(MediaType.APPLICATION_JSON).bodyValue(it) } ?: request).exchange()
    }

    private val extendBody: String = """{"expiresAt":"2026-10-07T21:50:00+09:00"}"""

    @Nested
    inner class `확정` {
        @Test
        fun `service role이 호출하면 200과 확정된 예약을 응답한다`() =
            runBlocking<Unit> {
                whenever(
                    confirmReservationUseCase.confirm(1L),
                ).thenReturn(result.copy(status = ReservationStatus.CONFIRMED, expiresAt = null))

                post("service", "confirm")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("CONFIRMED")
                    .jsonPath("$.expiresAt")
                    .isEmpty
                    .jsonPath("$.items[0].allocations.length()")
                    .isEqualTo(2)
                    .consumeWith(ReservationTransitionApiDocs.confirm())
            }

        @Test
        fun `경로의 예약 ID를 UseCase로 넘긴다`() =
            runBlocking<Unit> {
                whenever(confirmReservationUseCase.confirm(42L)).thenReturn(result)

                post("service", "confirm", reservationId = 42L).expectStatus().isOk

                verifyBlocking(confirmReservationUseCase) { confirm(42L) }
            }
    }

    @Nested
    inner class `해제` {
        @Test
        fun `service role이 호출하면 200과 해제된 예약을 응답한다`() =
            runBlocking<Unit> {
                whenever(
                    releaseReservationUseCase.release(1L),
                ).thenReturn(result.copy(status = ReservationStatus.RELEASED, expiresAt = null))

                post("service", "release")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("RELEASED")
                    .consumeWith(ReservationTransitionApiDocs.release())
            }
    }

    @Nested
    inner class `연장` {
        @Test
        fun `service role이 호출하면 200과 늘어난 만료 시각을 응답한다`() =
            runBlocking<Unit> {
                whenever(extendReservationUseCase.extend(any())).thenReturn(result.copy(expiresAt = LocalDateTime.of(2026, 10, 7, 21, 50)))

                post("service", "extend", extendBody)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.expiresAt")
                    .isEqualTo("2026-10-07T21:50:00+09:00")
                    .consumeWith(ReservationTransitionApiDocs.extend())
            }

        @Test
        fun `새 만료 시각을 서비스 시간대로 바꿔 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(extendReservationUseCase.extend(any())).thenReturn(result)

                post("service", "extend", """{"expiresAt":"2026-10-07T12:50:00Z"}""", reservationId = 7L).expectStatus().isOk

                val command = argumentCaptor<ExtendReservationCommand>()
                verifyBlocking(extendReservationUseCase) { extend(command.capture()) }
                assertEquals(ExtendReservationCommand(7L, LocalDateTime.of(2026, 10, 7, 21, 50)), command.firstValue)
            }

        @Test
        fun `만료 시각이 없거나 오프셋이 없거나 형식이 틀리면 400이고 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                listOf(
                    """{}""",
                    """{"expiresAt":"2026-10-07T21:50:00"}""",
                    """{"expiresAt":"20261007"}""",
                ).forEach { body ->
                    post("service", "extend", body)
                        .expectStatus()
                        .isBadRequest
                        .expectBody()
                        .jsonPath("$.code")
                        .isEqualTo("VALIDATION_FAILED")
                }
                post("service", "extend", """{"expiresAt":" "}""")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.errors.length()")
                    .isEqualTo(2)
                    .consumeWith(ReservationTransitionApiDocs.invalid())
                verifyBlocking(extendReservationUseCase, never()) { extend(any()) }
            }

        @Test
        fun `허용 범위를 벗어난 만료 시각은 400 INV_INVALID_RESERVATION_EXPIRY`() =
            runBlocking<Unit> {
                whenever(
                    extendReservationUseCase.extend(any()),
                ).thenThrow(InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_EXPIRY))

                post("service", "extend", extendBody)
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_EXPIRY")
                    .consumeWith(ReservationTransitionApiDocs.expiryOutOfRange())
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `service가 아닌 role은 403이고 토큰이 없으면 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                listOf("confirm" to null, "release" to null, "extend" to extendBody).forEach { (action, body) ->
                    post("warehouse_manager", action, body).expectStatus().isForbidden
                    post("admin", action, body).expectStatus().isForbidden
                    post(null, action, body).expectStatus().isUnauthorized
                }

                verifyBlocking(confirmReservationUseCase, never()) { confirm(any()) }
                verifyBlocking(releaseReservationUseCase, never()) { release(any()) }
                verifyBlocking(extendReservationUseCase, never()) { extend(any()) }
            }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `예약이 없으면 404 INV_RESERVATION_NOT_FOUND`() =
            runBlocking<Unit> {
                whenever(confirmReservationUseCase.confirm(1L)).thenThrow(ReservationNotFoundException())

                post("service", "confirm")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_NOT_FOUND")
                    .consumeWith(ReservationTransitionApiDocs.notFound())
            }

        @Test
        fun `불가능한 전이는 409 INV_INVALID_RESERVATION_STATE`() =
            runBlocking<Unit> {
                whenever(releaseReservationUseCase.release(1L)).thenThrow(InvalidReservationStateException())

                post("service", "release")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_STATE")
                    .consumeWith(ReservationTransitionApiDocs.conflict())
            }

        @Test
        fun `만료 시각이 지난 예약은 409 INV_RESERVATION_EXPIRED`() =
            runBlocking<Unit> {
                whenever(confirmReservationUseCase.confirm(1L)).thenThrow(ReservationExpiredException())

                post("service", "confirm")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_EXPIRED")
            }

        @Test
        fun `계속 겹쳐 처리하지 못하면 409 INV_RESERVATION_CHANGED_CONCURRENTLY`() =
            runBlocking<Unit> {
                whenever(extendReservationUseCase.extend(any())).thenThrow(ReservationChangedConcurrentlyException())

                post("service", "extend", extendBody)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RESERVATION_CHANGED_CONCURRENTLY")
            }
    }
}
