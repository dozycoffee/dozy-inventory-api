package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.global.security.Actor
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.global.security.UserActor
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryHistoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.SearchInventoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryHistoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
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
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.LocalDate
import java.time.LocalDateTime

@WebFluxTest(InventoryQueryController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class InventoryQueryControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var searchInventoriesUseCase: SearchInventoriesUseCase

    @MockitoBean
    private lateinit var getInventoryUseCase: GetInventoryUseCase

    @MockitoBean
    private lateinit var getInventoryHistoriesUseCase: GetInventoryHistoriesUseCase

    @MockitoBean
    private lateinit var resolveWarehouseScopeUseCase: ResolveWarehouseScopeUseCase

    private lateinit var webTestClient: WebTestClient

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 14, 9, 0)

    private val inventory: InventoryResult =
        InventoryResult(
            500L,
            10L,
            100L,
            7L,
            "LOT-A",
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 1, 1),
            LotStatus.NORMAL,
            QualityStatus.NORMAL,
            10,
            4,
            6,
            true,
            "피킹 불가",
            at,
            at,
            at,
        )

    private val history: InventoryHistoryResult =
        InventoryHistoryResult(9000L, HistoryType.INBOUND, 5, 15, ReferenceType.INBOUND_ITEM, 77L, "wms-inbound-77", "svc-wms", at)

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
        runBlocking { whenever(resolveWarehouseScopeUseCase.resolve(any())).thenReturn(WarehouseScope.All) }
    }

    private fun get(
        role: String?,
        uri: String,
        vararg variables: Any,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(uri, *variables)
            .headers { headers: HttpHeaders -> role?.let { headers.setBearerAuth(tokens.issue(roles = listOf("inventory:$it"))) } }
            .exchange()

    @Nested
    inner class `재고 현황` {
        @Test
        fun `admin이 조회하면 재고 행을 페이지로 응답한다`() =
            runBlocking<Unit> {
                whenever(searchInventoriesUseCase.search(any())).thenReturn(PageResult(listOf(inventory), 0, 20, 1L))

                get("admin", "/api/v1/inventories?warehouseId=10&qualityStatus=NORMAL&page=0&size=20")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items[0].inventoryId")
                    .isEqualTo(500)
                    .jsonPath("$.items[0].availableQuantity")
                    .isEqualTo(6)
                    .jsonPath("$.items[0].lotNumber")
                    .isEqualTo("LOT-A")
                    .jsonPath("$.items[0].allocationHold")
                    .isEqualTo(true)
                    .jsonPath("$.totalElements")
                    .isEqualTo(1)
                    .jsonPath("$.totalPages")
                    .isEqualTo(1)
                    .consumeWith(InventoryQueryApiDocs.search())
            }

        @Test
        fun `조건과 페이지를 Query로 넘기고 생략하면 기본값을 쓴다`() =
            runBlocking<Unit> {
                whenever(searchInventoriesUseCase.search(any())).thenReturn(PageResult(emptyList(), 2, 5, 0L))

                get(
                    "admin",
                    "/api/v1/inventories?warehouseId=10&productId=100&qualityStatus=DEFECTIVE&lotStatus=EXPIRED&allocationHold=false&page=2&size=5",
                ).expectStatus()
                    .isOk
                get("admin", "/api/v1/inventories").expectStatus().isOk

                val query = argumentCaptor<SearchInventoriesQuery>()
                verifyBlocking(searchInventoriesUseCase, org.mockito.kotlin.times(2)) { search(query.capture()) }
                assertEquals(
                    SearchInventoriesQuery(10L, 100L, QualityStatus.DEFECTIVE, LotStatus.EXPIRED, false, 2, 5, WarehouseScope.All),
                    query.firstValue,
                )
                assertEquals(SearchInventoriesQuery(null, null, null, null, null, 0, 20, WarehouseScope.All), query.secondValue)
            }

        @Test
        fun `조건이 올바르지 않으면 400`() =
            runBlocking<Unit> {
                whenever(
                    searchInventoriesUseCase.search(any()),
                ).thenThrow(InvalidDomainValueException(InventoryErrorCode.INVALID_INVENTORY_QUERY))

                get("admin", "/api/v1/inventories?size=101")
                    .expectStatus()
                    .isBadRequest
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_INVENTORY_QUERY")
                    .consumeWith(InventoryQueryApiDocs.invalid())
                get("admin", "/api/v1/inventories?qualityStatus=UNKNOWN").expectStatus().isBadRequest
                get("admin", "/api/v1/inventories?warehouseId=abc").expectStatus().isBadRequest
            }
    }

    @Nested
    inner class `상세와 이력` {
        @Test
        fun `상세를 응답한다`() =
            runBlocking<Unit> {
                whenever(getInventoryUseCase.getById(500L, WarehouseScope.All)).thenReturn(inventory)

                get("admin", "/api/v1/inventories/{inventoryId}", 500L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.inventoryId")
                    .isEqualTo(500)
                    .jsonPath("$.holdReason")
                    .isEqualTo("피킹 불가")
                    .jsonPath("$.expirationDate")
                    .isEqualTo("2027-01-01")
                    .consumeWith(InventoryQueryApiDocs.detail())
            }

        @Test
        fun `없는 재고 행은 404`() =
            runBlocking<Unit> {
                whenever(getInventoryUseCase.getById(500L, WarehouseScope.All)).thenThrow(InventoryNotFoundException())

                get("admin", "/api/v1/inventories/{inventoryId}", 500L)
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVENTORY_NOT_FOUND")
                    .consumeWith(InventoryQueryApiDocs.notFound())
            }

        @Test
        fun `이력을 최신순 페이지로 응답하고 페이지 기본값을 쓴다`() =
            runBlocking<Unit> {
                whenever(
                    getInventoryHistoriesUseCase.getHistories(500L, WarehouseScope.All, 0, 20),
                ).thenReturn(PageResult(listOf(history), 0, 20, 21L))

                get("admin", "/api/v1/inventories/{inventoryId}/histories", 500L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items[0].historyType")
                    .isEqualTo("INBOUND")
                    .jsonPath("$.items[0].quantityAfter")
                    .isEqualTo(15)
                    .jsonPath("$.items[0].requesterService")
                    .isEqualTo("svc-wms")
                    .jsonPath("$.totalPages")
                    .isEqualTo(2)
                    .consumeWith(InventoryQueryApiDocs.histories())
            }

        @Test
        fun `이력의 재고 행이 없으면 404`() =
            runBlocking<Unit> {
                whenever(getInventoryHistoriesUseCase.getHistories(500L, WarehouseScope.All, 1, 5)).thenThrow(InventoryNotFoundException())

                get("admin", "/api/v1/inventories/{inventoryId}/histories?page=1&size=5", 500L).expectStatus().isNotFound
            }
    }

    @Nested
    inner class `권한과 창고 범위` {
        @Test
        fun `service role과 토큰이 없는 요청은 403과 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                listOf("/api/v1/inventories", "/api/v1/inventories/500", "/api/v1/inventories/500/histories").forEach { uri ->
                    get("service", uri).expectStatus().isForbidden
                    get(null, uri).expectStatus().isUnauthorized
                }

                verifyBlocking(searchInventoriesUseCase, never()) { search(any()) }
                verifyBlocking(getInventoryUseCase, never()) { getById(any(), any()) }
                verifyBlocking(getInventoryHistoriesUseCase, never()) { getHistories(any(), any(), any(), any()) }
                verifyBlocking(resolveWarehouseScopeUseCase, never()) { resolve(any()) }
            }

        @Test
        fun `창고 관리자도 조회할 수 있고 요청자의 범위를 조회 UseCase에 넘긴다`() =
            runBlocking<Unit> {
                val scope = WarehouseScope.Only(setOf(10L, 30L))
                whenever(resolveWarehouseScopeUseCase.resolve(any())).thenReturn(scope)
                whenever(searchInventoriesUseCase.search(any())).thenReturn(PageResult(listOf(inventory), 0, 20, 1L))
                whenever(getInventoryUseCase.getById(500L, scope)).thenReturn(inventory)
                whenever(getInventoryHistoriesUseCase.getHistories(500L, scope, 0, 20)).thenReturn(PageResult(listOf(history), 0, 20, 1L))

                get("warehouse_manager", "/api/v1/inventories").expectStatus().isOk
                get("warehouse_manager", "/api/v1/inventories/500").expectStatus().isOk
                get("warehouse_manager", "/api/v1/inventories/500/histories").expectStatus().isOk

                val query = argumentCaptor<SearchInventoriesQuery>()
                verifyBlocking(searchInventoriesUseCase) { search(query.capture()) }
                assertEquals(scope, query.firstValue.scope)
                val actor = argumentCaptor<Actor>()
                verifyBlocking(resolveWarehouseScopeUseCase, org.mockito.kotlin.times(3)) { resolve(actor.capture()) }
                assertEquals(setOf("warehouse_manager"), (actor.firstValue as UserActor).roles)
            }

        @Test
        fun `배정되지 않은 창고이면 403 INV_WAREHOUSE_ACCESS_DENIED`() =
            runBlocking<Unit> {
                whenever(searchInventoriesUseCase.search(any())).thenThrow(WarehouseAccessDeniedException())

                get("warehouse_manager", "/api/v1/inventories?warehouseId=20")
                    .expectStatus()
                    .isForbidden
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_WAREHOUSE_ACCESS_DENIED")
                    .consumeWith(InventoryQueryApiDocs.warehouseAccessDenied())
            }

        @Test
        fun `소속 사본이 없는 창고 관리자는 범위를 구하는 단계에서 403이고 조회하지 않는다`() =
            runBlocking<Unit> {
                whenever(resolveWarehouseScopeUseCase.resolve(any())).thenThrow(WarehouseAccessDeniedException())

                get("warehouse_manager", "/api/v1/inventories").expectStatus().isForbidden

                verifyBlocking(searchInventoriesUseCase, never()) { search(any()) }
            }
    }
}
