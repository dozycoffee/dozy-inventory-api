package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmReturnUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ReturnResult
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
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

@WebFluxTest(ReturnController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class ReturnControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var confirmReturnUseCase: ConfirmReturnUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: ReturnResult = ReturnResult(500L, 10L, 100L, 1000L, QualityStatus.DEFECTIVE, 4, 14, 9000L)

    private val body: String =
        """{"warehouseId":10,"productId":100,"lotNumber":"LOT-A","qualityStatus":"DEFECTIVE","quantity":4,"returnItemId":5}"""

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "wms-return-item-5",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/returns")
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `반품 복귀` {
        @Test
        fun `service role이 호출하면 200과 처리 후 수량을 응답한다`() =
            runBlocking<Unit> {
                whenever(confirmReturnUseCase.confirm(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.inventoryId")
                    .isEqualTo(500)
                    .jsonPath("$.quantityChange")
                    .isEqualTo(4)
                    .jsonPath("$.quantityAfter")
                    .isEqualTo(14)
                    .jsonPath("$.qualityStatus")
                    .isEqualTo("DEFECTIVE")
                    .consumeWith(ReturnApiDocs.confirm())
            }

        @Test
        fun `요청 본문과 멱등 키 헤더와 요청 주체를 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(confirmReturnUseCase.confirm(any())).thenReturn(result)

                post("service").expectStatus().isOk

                val command = argumentCaptor<ConfirmReturnCommand>()
                verifyBlocking(confirmReturnUseCase) { confirm(command.capture()) }
                val actual: ConfirmReturnCommand = command.firstValue
                assertEquals(10L, actual.warehouseId)
                assertEquals(100L, actual.productId)
                assertEquals("LOT-A", actual.lotNumber)
                assertEquals(QualityStatus.DEFECTIVE, actual.qualityStatus)
                assertEquals(4, actual.quantity)
                assertEquals(5L, actual.referenceId)
                assertEquals("wms-return-item-5", actual.idempotencyKey)
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

                verifyBlocking(confirmReturnUseCase, never()) { confirm(any()) }
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
                """{"warehouseId":0,"productId":100,"lotNumber":" ","qualityStatus":"DEFECTIVE","quantity":-1,"returnItemId":5}""",
            ).expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(3)
                .consumeWith(ReturnApiDocs.invalid())
        }

        @Test
        fun `Lot 번호가 51자이거나 알 수 없는 품질 상태이면 400`() {
            post("service", body.replace("LOT-A", "L".repeat(51))).expectStatus().isBadRequest
            post("service", body.replace("DEFECTIVE", "UNKNOWN")).expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `폐기 예정 품질 상태는 400 INV_INVALID_RETURN_QUALITY_STATUS`() =
            runBlocking<Unit> {
                whenever(
                    confirmReturnUseCase.confirm(any()),
                ).thenThrow(InvalidDomainValueException(InventoryErrorCode.INVALID_RETURN_QUALITY_STATUS))

                post("service", body.replace("DEFECTIVE", "DISPOSAL_SCHEDULED"))
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_RETURN_QUALITY_STATUS")
            }

        @Test
        fun `상품이나 Lot이 없으면 404`() =
            runBlocking<Unit> {
                whenever(confirmReturnUseCase.confirm(any())).thenThrow(ProductNotFoundException()).thenThrow(LotNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")
                    .consumeWith(ReturnApiDocs.notFound())
                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_NOT_FOUND")
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(confirmReturnUseCase.confirm(any())).thenThrow(IdempotencyKeyConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
                    .consumeWith(ReturnApiDocs.conflict())
            }
    }
}
