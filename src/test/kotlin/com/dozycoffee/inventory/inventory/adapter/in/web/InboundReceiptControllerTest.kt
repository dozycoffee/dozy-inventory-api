package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmInboundUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult
import com.dozycoffee.inventory.inventory.domain.exception.LotMismatchException
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
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

@WebFluxTest(InboundReceiptController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class InboundReceiptControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var confirmInboundUseCase: ConfirmInboundUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: InboundResult = InboundResult(500L, 10L, 100L, 1000L, QualityStatus.NORMAL, 5, 15, 9000L)

    private val body: String =
        """
        {"warehouseId":10,"productId":100,"quantity":5,"qualityStatus":"NORMAL","lotNumber":"LOT-A",
         "manufactureDate":"2026-09-01","expirationDate":"2027-03-01","inboundItemId":77}
        """.trimIndent()

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "wms-inbound-item-77",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/inbound-receipts")
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `입고 확정` {
        @Test
        fun `service role이 호출하면 200과 처리 후 수량을 응답한다`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.inventoryId")
                    .isEqualTo(500)
                    .jsonPath("$.quantityAfter")
                    .isEqualTo(15)
                    .jsonPath("$.quantityChange")
                    .isEqualTo(5)
                    .jsonPath("$.qualityStatus")
                    .isEqualTo("NORMAL")
                    .consumeWith(InboundReceiptApiDocs.confirm())
            }

        @Test
        fun `요청 본문과 멱등 키 헤더와 요청 주체를 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenReturn(result)

                post("service").expectStatus().isOk

                val command = argumentCaptor<ConfirmInboundCommand>()
                verifyBlocking(confirmInboundUseCase) { confirm(command.capture()) }
                val actual: ConfirmInboundCommand = command.firstValue
                assertEquals(10L, actual.warehouseId)
                assertEquals(100L, actual.productId)
                assertEquals(5, actual.quantity)
                assertEquals(QualityStatus.NORMAL, actual.qualityStatus)
                assertEquals("LOT-A", actual.lotNumber)
                assertEquals("2026-09-01", actual.manufactureDate.toString())
                assertEquals("2027-03-01", actual.expirationDate.toString())
                assertEquals(77L, actual.referenceId)
                assertEquals("wms-inbound-item-77", actual.idempotencyKey)
                assertEquals("00000000-0000-7000-8000-000000000001", actual.requesterService)
            }

        @Test
        fun `제조일자와 유통기한은 생략할 수 있다`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenReturn(result)

                post(
                    "service",
                    """{"warehouseId":10,"productId":100,"quantity":5,"qualityStatus":"DEFECTIVE","lotNumber":"L","inboundItemId":1}""",
                ).expectStatus()
                    .isOk
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

                verifyBlocking(confirmInboundUseCase, never()) { confirm(any()) }
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
                """{"warehouseId":0,"productId":100,"quantity":-1,"qualityStatus":"NORMAL","lotNumber":" ","inboundItemId":1}""",
            ).expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(3)
                .consumeWith(InboundReceiptApiDocs.invalid())
        }

        @Test
        fun `Lot 번호가 51자이면 400`() {
            post("service", body.replace("LOT-A", "L".repeat(51))).expectStatus().isBadRequest
        }

        @Test
        fun `알 수 없는 품질 상태나 날짜 형식은 400`() {
            post("service", body.replace("NORMAL", "UNKNOWN")).expectStatus().isBadRequest
            post("service", body.replace("2026-09-01", "20260901")).expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `상품이 없으면 404 INV_PRODUCT_NOT_FOUND`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenThrow(ProductNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")
                    .consumeWith(InboundReceiptApiDocs.productNotFound())
            }

        @Test
        fun `Lot 날짜가 다르면 409 INV_LOT_MISMATCH`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenThrow(LotMismatchException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_MISMATCH")
                    .consumeWith(InboundReceiptApiDocs.lotMismatch())
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(confirmInboundUseCase.confirm(any())).thenThrow(IdempotencyKeyConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }
    }
}
