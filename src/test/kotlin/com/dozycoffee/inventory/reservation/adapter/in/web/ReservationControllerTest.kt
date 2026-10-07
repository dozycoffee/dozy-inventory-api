package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateOrderReservationException
import com.dozycoffee.inventory.reservation.domain.exception.ProductNotReservableException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
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

@WebFluxTest(ReservationController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class, ClockConfig::class)
@ExtendWith(RestDocumentationExtension::class)
class ReservationControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var createReservationUseCase: CreateReservationUseCase

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

    private val body: String =
        """
        {"warehouseId":10,"channel":"OMS","externalOrderId":"ORDER-1","expiresAt":"2026-10-07T21:30:00+09:00",
         "items":[{"productId":100,"quantity":10}]}
        """.trimIndent()

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "svc-oms-order-1",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/reservations")
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `예약 생성` {
        @Test
        fun `service role이 호출하면 200과 예약과 할당 내역을 응답한다`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.reservationId")
                    .isEqualTo(1)
                    .jsonPath("$.status")
                    .isEqualTo("RESERVED")
                    .jsonPath("$.expiresAt")
                    .isEqualTo("2026-10-07T21:30:00+09:00")
                    .jsonPath("$.maxExpiresAt")
                    .isEqualTo("2026-10-07T22:00:00+09:00")
                    .jsonPath("$.items[0].allocations.length()")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].allocations[1].inventoryId")
                    .isEqualTo(7)
                    .consumeWith(ReservationApiDocs.create())
            }

        @Test
        fun `확정된 예약은 만료 시각이 null이다`() =
            runBlocking<Unit> {
                whenever(
                    createReservationUseCase.create(any()),
                ).thenReturn(result.copy(status = ReservationStatus.CONFIRMED, expiresAt = null))

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.expiresAt")
                    .isEmpty
                    .jsonPath("$.status")
                    .isEqualTo("CONFIRMED")
            }

        @Test
        fun `요청 본문과 멱등 키 헤더와 요청 주체를 Command로 넘기고 만료 시각을 서비스 시간대로 바꾼다`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenReturn(result)

                post("service").expectStatus().isOk

                val command = argumentCaptor<CreateReservationCommand>()
                verifyBlocking(createReservationUseCase) { create(command.capture()) }
                val actual: CreateReservationCommand = command.firstValue
                assertEquals(10L, actual.warehouseId)
                assertEquals("OMS", actual.channel)
                assertEquals("ORDER-1", actual.externalOrderId)
                assertEquals(listOf(CreateReservationCommand.Item(100L, 10)), actual.items)
                assertEquals(LocalDateTime.of(2026, 10, 7, 21, 30), actual.expiresAt)
                assertEquals("svc-oms-order-1", actual.idempotencyKey)
                assertEquals("00000000-0000-7000-8000-000000000001", actual.requesterService)
            }

        @Test
        fun `다른 시간대의 오프셋은 같은 순간의 서비스 시각으로 바뀐다`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenReturn(result)

                post("service", body.replace("2026-10-07T21:30:00+09:00", "2026-10-07T12:30:00Z")).expectStatus().isOk

                val command = argumentCaptor<CreateReservationCommand>()
                verifyBlocking(createReservationUseCase) { create(command.capture()) }
                assertEquals(LocalDateTime.of(2026, 10, 7, 21, 30), command.firstValue.expiresAt)
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

                verifyBlocking(createReservationUseCase, never()) { create(any()) }
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
            post(
                "service",
                """{"warehouseId":0,"channel":" ","externalOrderId":"O","expiresAt":"2026-10-07T21:30:00+09:00","items":[{"productId":100,"quantity":0}]}""",
            ).expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(3)
                .consumeWith(ReservationApiDocs.invalid())
        }

        @Test
        fun `상품이 없거나 101개이면 400`() {
            post("service", body.replace(""""items":[{"productId":100,"quantity":10}]""", """"items":[]""")).expectStatus().isBadRequest
            val items: String = (1..101).joinToString(",") { """{"productId":$it,"quantity":1}""" }
            post("service", body.replace("""{"productId":100,"quantity":10}""", items)).expectStatus().isBadRequest
        }

        @Test
        fun `채널과 주문 ID의 길이 상한을 넘으면 400`() {
            post("service", body.replace("OMS", "C".repeat(51))).expectStatus().isBadRequest
            post("service", body.replace("ORDER-1", "O".repeat(101))).expectStatus().isBadRequest
        }

        @Test
        fun `시간대 오프셋이 없는 만료 시각은 400이고 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                post("service", body.replace("+09:00", "")).expectStatus().isBadRequest

                verifyBlocking(createReservationUseCase, never()) { create(any()) }
            }

        @Test
        fun `날짜만 있거나 형식이 틀린 만료 시각은 400`() {
            listOf("2026-10-07", "20261007", "2026-13-40T21:30:00+09:00", "", "now").forEach { value: String ->
                post("service", body.replace("2026-10-07T21:30:00+09:00", value)).expectStatus().isBadRequest
            }
            post("service", body.replace(""""expiresAt":"2026-10-07T21:30:00+09:00",""", "")).expectStatus().isBadRequest
        }

        @Test
        fun `만료 시각이 허용 범위를 벗어나면 400 INV_INVALID_RESERVATION_EXPIRY`() =
            runBlocking<Unit> {
                whenever(
                    createReservationUseCase.create(any()),
                ).thenThrow(InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_EXPIRY))

                post("service")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RESERVATION_EXPIRY")
            }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `상품이 없으면 404 INV_PRODUCT_NOT_FOUND`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(ProductNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")
                    .consumeWith(ReservationApiDocs.productNotFound())
            }

        @Test
        fun `가용 수량이 부족하면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(InsufficientAvailableQuantityException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")
                    .consumeWith(ReservationApiDocs.insufficientQuantity())
            }

        @Test
        fun `할당 경합으로 밀리면 409 INV_ALLOCATION_CONFLICT`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(AllocationConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_ALLOCATION_CONFLICT")
            }

        @Test
        fun `같은 주문에 살아 있는 예약이 있으면 409 INV_DUPLICATE_ORDER_RESERVATION`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(DuplicateOrderReservationException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_DUPLICATE_ORDER_RESERVATION")
                    .consumeWith(ReservationApiDocs.conflict())
            }

        @Test
        fun `비활성 상품은 409 INV_PRODUCT_NOT_RESERVABLE`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(ProductNotReservableException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_RESERVABLE")
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(createReservationUseCase.create(any())).thenThrow(IdempotencyKeyConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }
    }
}
