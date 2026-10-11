package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.adjustment.application.port.`in`.RequestStockAdjustmentUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.ApprovalRequiredException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
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

@WebFluxTest(StockAdjustmentController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class StockAdjustmentControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var requestStockAdjustmentUseCase: RequestStockAdjustmentUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: StockAdjustmentResult =
        StockAdjustmentResult(
            9L,
            10L,
            77L,
            AdjustmentStatus.APPLIED,
            "manager-7",
            listOf(
                StockAdjustmentResult.Item(1001L, 500L, 1L, QualityStatus.NORMAL, -4, 6),
                StockAdjustmentResult.Item(1002L, 501L, 2L, QualityStatus.DEFECTIVE, 3, 3),
            ),
        )

    private val body: String =
        """
        {"warehouseId":10,"auditId":77,"approvedBy":"manager-7","items":[
          {"productId":100,"lotNumber":"LOT-A","qualityStatus":"NORMAL","quantityChange":-4},
          {"productId":100,"lotNumber":"LOT-B","qualityStatus":"DEFECTIVE","quantityChange":3}]}
        """.trimIndent()

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun post(
        role: String?,
        requestBody: String = body,
        key: String? = "wms-audit-1001",
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/stock-adjustments")
            .headers { headers: HttpHeaders ->
                role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) }
                key?.let { headers.set("Idempotency-Key", it) }
            }.contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .exchange()

    @Nested
    inner class `실사 조정` {
        @Test
        fun `service role이 호출하면 200과 항목별 반영 후 수량을 응답한다`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenReturn(result)

                post("service")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.stockAdjustmentId")
                    .isEqualTo(9)
                    .jsonPath("$.auditId")
                    .isEqualTo(77)
                    .jsonPath("$.status")
                    .isEqualTo("APPLIED")
                    .jsonPath("$.approvedBy")
                    .isEqualTo("manager-7")
                    .jsonPath("$.items.length()")
                    .isEqualTo(2)
                    .jsonPath("$.items[0].quantityChange")
                    .isEqualTo(-4)
                    .jsonPath("$.items[0].quantityAfter")
                    .isEqualTo(6)
                    .jsonPath("$.items[1].qualityStatus")
                    .isEqualTo("DEFECTIVE")
                    .consumeWith(StockAdjustmentApiDocs.request())
            }

        @Test
        fun `요청 본문과 멱등 키 헤더와 요청 주체를 Command로 넘긴다`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenReturn(result)

                post("service").expectStatus().isOk

                val command = argumentCaptor<RequestStockAdjustmentCommand>()
                verifyBlocking(requestStockAdjustmentUseCase) { request(command.capture()) }
                val actual: RequestStockAdjustmentCommand = command.firstValue
                assertEquals(10L, actual.warehouseId)
                assertEquals(77L, actual.externalReferenceId)
                assertEquals("manager-7", actual.approvedBy)
                assertEquals(listOf("LOT-A", "LOT-B"), actual.items.map { it.lotNumber })
                assertEquals(listOf(-4, 3), actual.items.map { it.quantityChange })
                assertEquals(listOf(QualityStatus.NORMAL, QualityStatus.DEFECTIVE), actual.items.map { it.qualityStatus })
                assertEquals("wms-audit-1001", actual.idempotencyKey)
                assertEquals("00000000-0000-7000-8000-000000000001", actual.requesterService)
            }

        @Test
        fun `승인자는 생략할 수 있고 null로 넘어간다`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenReturn(result)

                post(
                    "service",
                    """{"warehouseId":10,"auditId":77,"items":[{"productId":100,"lotNumber":"L","qualityStatus":"NORMAL","quantityChange":1}]}""",
                ).expectStatus()
                    .isOk

                val command = argumentCaptor<RequestStockAdjustmentCommand>()
                verifyBlocking(requestStockAdjustmentUseCase) { request(command.capture()) }
                assertEquals(null, command.firstValue.approvedBy)
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

                verifyBlocking(requestStockAdjustmentUseCase, never()) { request(any()) }
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
                """{"warehouseId":0,"auditId":-1,"items":[{"productId":100,"lotNumber":" ","qualityStatus":"NORMAL","quantityChange":1}]}""",
            ).expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.length()")
                .isEqualTo(3)
                .consumeWith(StockAdjustmentApiDocs.invalid())
        }

        @Test
        fun `항목이 없거나 500개를 넘으면 400`() {
            post("service", """{"warehouseId":10,"auditId":77,"items":[]}""").expectStatus().isBadRequest
            val items: String =
                (1..501).joinToString(",") { """{"productId":100,"lotNumber":"L$it","qualityStatus":"NORMAL","quantityChange":1}""" }
            post("service", """{"warehouseId":10,"auditId":77,"items":[$items]}""").expectStatus().isBadRequest
        }

        @Test
        fun `변동량이 0이면 400 INV_INVALID_STOCK_ADJUSTMENT`() {
            post("service", body.replace("-4", "0"))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_STOCK_ADJUSTMENT")
        }

        @Test
        fun `같은 상품 Lot 품질 상태가 중복되면 400`() {
            post("service", body.replace("LOT-B", "LOT-A").replace("DEFECTIVE", "NORMAL"))
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_STOCK_ADJUSTMENT")
        }

        @Test
        fun `승인자가 101자이거나 알 수 없는 품질 상태이면 400`() {
            post("service", body.replace("manager-7", "m".repeat(101))).expectStatus().isBadRequest
            post("service", body.replace("DEFECTIVE", "UNKNOWN")).expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `서비스 오류` {
        @Test
        fun `승인이 필요한데 승인자가 없으면 400 INV_APPROVAL_REQUIRED`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenThrow(ApprovalRequiredException())

                post("service")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_APPROVAL_REQUIRED")
                    .consumeWith(StockAdjustmentApiDocs.approvalRequired())
            }

        @Test
        fun `상품이나 Lot이나 재고 행이 없으면 404`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any()))
                    .thenThrow(LotNotFoundException())
                    .thenThrow(ProductNotFoundException())
                    .thenThrow(InventoryNotFoundException())

                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_LOT_NOT_FOUND")
                    .consumeWith(StockAdjustmentApiDocs.notFound())
                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_PRODUCT_NOT_FOUND")
                post("service")
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVENTORY_NOT_FOUND")
            }

        @Test
        fun `가용 수량을 넘는 감소는 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenThrow(InsufficientAvailableQuantityException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")
                    .consumeWith(StockAdjustmentApiDocs.conflict())
            }

        @Test
        fun `같은 키에 다른 내용이면 409 INV_IDEMPOTENCY_KEY_CONFLICT`() =
            runBlocking<Unit> {
                whenever(requestStockAdjustmentUseCase.request(any())).thenThrow(IdempotencyKeyConflictException())

                post("service")
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_IDEMPOTENCY_KEY_CONFLICT")
            }
    }
}
