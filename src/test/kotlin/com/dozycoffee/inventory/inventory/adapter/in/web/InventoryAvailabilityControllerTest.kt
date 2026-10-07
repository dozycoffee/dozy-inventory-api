package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAvailabilityUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.GetAvailabilityQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AvailabilityResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ProductAvailability
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseAvailability
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
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(InventoryAvailabilityController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class InventoryAvailabilityControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var getAvailabilityUseCase: GetAvailabilityUseCase

    private lateinit var webTestClient: WebTestClient

    private val result: AvailabilityResult =
        AvailabilityResult(
            listOf(
                ProductAvailability(1L, 120L, listOf(WarehouseAvailability(10L, 100L), WarehouseAvailability(20L, 20L))),
                ProductAvailability(2L, 0L, emptyList()),
            ),
        )

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private fun get(
        role: String?,
        uri: String,
        vararg variables: Any,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(uri, *variables)
            .headers { headers -> role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) } }
            .exchange()

    private fun capturedQuery(): GetAvailabilityQuery {
        val query = argumentCaptor<GetAvailabilityQuery>()
        runBlocking { verifyBlocking(getAvailabilityUseCase) { getAvailability(query.capture()) } }
        return query.firstValue
    }

    @Nested
    inner class `조회` {
        @Test
        fun `service가 호출하면 200과 상품별 창고별 수량과 합계를 응답한다`() =
            runBlocking<Unit> {
                whenever(getAvailabilityUseCase.getAvailability(any())).thenReturn(result)

                get("service", "/api/v1/inventories/availability?productIds={productIds}&warehouseIds={warehouseIds}", "1,2", "10,20")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.products.length()")
                    .isEqualTo(2)
                    .jsonPath("$.products[0].productId")
                    .isEqualTo(1)
                    .jsonPath("$.products[0].totalAvailableQuantity")
                    .isEqualTo(120)
                    .jsonPath("$.products[0].warehouses[1].warehouseId")
                    .isEqualTo(20)
                    .jsonPath("$.products[0].warehouses[1].availableQuantity")
                    .isEqualTo(20)
                    .jsonPath("$.products[1].warehouses.length()")
                    .isEqualTo(0)
                    .consumeWith(AvailabilityApiDocs.availability())
            }

        @Test
        fun `admin도 호출할 수 있다`() =
            runBlocking<Unit> {
                whenever(getAvailabilityUseCase.getAvailability(any())).thenReturn(result)

                get("admin", "/api/v1/inventories/availability?productIds=1").expectStatus().isOk
            }
    }

    @Nested
    inner class `파라미터 변환` {
        @Test
        fun `쉼표로 구분한 목록을 집합으로 바꾸고 중복은 합친다`() =
            runBlocking<Unit> {
                whenever(getAvailabilityUseCase.getAvailability(any())).thenReturn(result)

                get("service", "/api/v1/inventories/availability?productIds=3,1,3,2&warehouseIds=20,10").expectStatus().isOk

                val query: GetAvailabilityQuery = capturedQuery()
                assertEquals(setOf(1L, 2L, 3L), query.productIds)
                assertEquals(setOf(10L, 20L), query.warehouseIds)
            }

        @Test
        fun `반복해서 보내도 받는다`() =
            runBlocking<Unit> {
                whenever(getAvailabilityUseCase.getAvailability(any())).thenReturn(result)

                get(
                    "service",
                    "/api/v1/inventories/availability?productIds=1&productIds=2&warehouseIds=10&warehouseIds=20",
                ).expectStatus().isOk

                val query: GetAvailabilityQuery = capturedQuery()
                assertEquals(setOf(1L, 2L), query.productIds)
                assertEquals(setOf(10L, 20L), query.warehouseIds)
            }

        @Test
        fun `창고를 생략하면 전체 창고(null)로 넘긴다`() =
            runBlocking<Unit> {
                whenever(getAvailabilityUseCase.getAvailability(any())).thenReturn(result)

                get("service", "/api/v1/inventories/availability?productIds=1").expectStatus().isOk

                assertEquals(null, capturedQuery().warehouseIds)
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `warehouse_manager는 403이고 토큰이 없으면 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                get("warehouse_manager", "/api/v1/inventories/availability?productIds=1").expectStatus().isForbidden
                get(null, "/api/v1/inventories/availability?productIds=1").expectStatus().isUnauthorized

                verifyBlocking(getAvailabilityUseCase, never()) { getAvailability(any()) }
            }
    }

    @Nested
    inner class `요청 검증` {
        @Test
        fun `조건 위반은 400 INV_INVALID_AVAILABILITY_QUERY`() {
            get("service", "/api/v1/inventories/availability?productIds=")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INV_INVALID_AVAILABILITY_QUERY")
                .consumeWith(AvailabilityApiDocs.invalid())
        }

        @Test
        fun `상품이 101개이거나 ID가 양수가 아니면 400`() {
            val tooMany: String = (1..101).joinToString(",")
            get("service", "/api/v1/inventories/availability?productIds={ids}", tooMany).expectStatus().isBadRequest
            get("service", "/api/v1/inventories/availability?productIds=1,0").expectStatus().isBadRequest
            get("service", "/api/v1/inventories/availability?productIds=1&warehouseIds=-1").expectStatus().isBadRequest
        }

        @Test
        fun `필수 파라미터가 없거나 숫자가 아니면 400 VALIDATION_FAILED`() {
            get("service", "/api/v1/inventories/availability")
                .expectStatus()
                .isBadRequest
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
            get("service", "/api/v1/inventories/availability?productIds=a,b").expectStatus().isBadRequest
        }
    }
}
