package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.ApproveReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.GetReconciliationProposalUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.RejectReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.SearchReconciliationProposalsUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.SearchReconciliationProposalsQuery
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationAdminRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationChangedException
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.global.security.SecurityConfig
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
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
import org.springframework.http.HttpMethod
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.LocalDateTime

@WebFluxTest(ReconciliationController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class ReconciliationControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var searchReconciliationProposalsUseCase: SearchReconciliationProposalsUseCase

    @MockitoBean
    private lateinit var getReconciliationProposalUseCase: GetReconciliationProposalUseCase

    @MockitoBean
    private lateinit var approveReconciliationUseCase: ApproveReconciliationUseCase

    @MockitoBean
    private lateinit var rejectReconciliationUseCase: RejectReconciliationUseCase

    @MockitoBean
    private lateinit var resolveWarehouseScopeUseCase: ResolveWarehouseScopeUseCase

    private lateinit var webTestClient: WebTestClient

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 12, 10, 0)

    private fun proposal(status: AdjustmentStatus = AdjustmentStatus.PENDING): ReconciliationProposalResult =
        ReconciliationProposalResult(
            1L,
            10L,
            5L,
            status,
            100L,
            7L,
            "LOT-A",
            QualityStatus.NORMAL,
            100,
            92,
            -8,
            false,
            if (status == AdjustmentStatus.APPLIED) 70L else null,
            if (status == AdjustmentStatus.APPLIED) "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73" else null,
            if (status == AdjustmentStatus.APPLIED) at else null,
        )

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
        runBlocking { whenever(resolveWarehouseScopeUseCase.resolve(any())).thenReturn(WarehouseScope.All) }
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

    private val base: String = "/api/v1/reconciliations/proposals"

    @Nested
    inner class `조회` {
        @Test
        fun `목록을 페이지로 응답한다`() =
            runBlocking<Unit> {
                whenever(searchReconciliationProposalsUseCase.search(any())).thenReturn(PageResult(listOf(proposal()), 0, 20, 1L))

                call(HttpMethod.GET, "admin", "$base?warehouseId=10&status=PENDING&page=0&size=20")
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.items[0].stockAdjustmentId")
                    .isEqualTo(1)
                    .jsonPath("$.items[0].quantityChange")
                    .isEqualTo(-8)
                    .jsonPath("$.items[0].status")
                    .isEqualTo("PENDING")
                    .jsonPath("$.items[0].inventoryId")
                    .isEmpty
                    .jsonPath("$.totalPages")
                    .isEqualTo(1)
                    .consumeWith(ReconciliationApiDocs.search())
            }

        @Test
        fun `조건과 페이지와 요청자의 범위를 Query로 넘기고 생략하면 기본값이다`() =
            runBlocking<Unit> {
                val scope = WarehouseScope.Only(setOf(10L, 30L))
                whenever(resolveWarehouseScopeUseCase.resolve(any())).thenReturn(scope)
                whenever(searchReconciliationProposalsUseCase.search(any())).thenReturn(PageResult(emptyList(), 0, 20, 0L))

                call(HttpMethod.GET, "warehouse_manager", "$base?warehouseId=10&status=APPLIED&page=2&size=5").expectStatus().isOk
                call(HttpMethod.GET, "warehouse_manager", base).expectStatus().isOk

                val query = argumentCaptor<SearchReconciliationProposalsQuery>()
                verifyBlocking(searchReconciliationProposalsUseCase, org.mockito.kotlin.times(2)) { search(query.capture()) }
                assertEquals(SearchReconciliationProposalsQuery(10L, AdjustmentStatus.APPLIED, 2, 5, scope), query.firstValue)
                assertEquals(SearchReconciliationProposalsQuery(null, null, 0, 20, scope), query.secondValue)
            }

        @Test
        fun `상세를 응답하고 없는 보정안은 404다`() =
            runBlocking<Unit> {
                whenever(getReconciliationProposalUseCase.getById(1L, WarehouseScope.All)).thenReturn(proposal(AdjustmentStatus.APPLIED))
                whenever(getReconciliationProposalUseCase.getById(2L, WarehouseScope.All)).thenThrow(AdjustmentNotFoundException())

                call(HttpMethod.GET, "admin", "$base/{stockAdjustmentId}", 1L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("APPLIED")
                    .jsonPath("$.inventoryId")
                    .isEqualTo(70)
                    .jsonPath("$.lotNumber")
                    .isEqualTo("LOT-A")
                    .consumeWith(ReconciliationApiDocs.detail())
                call(HttpMethod.GET, "admin", "$base/{stockAdjustmentId}", 2L)
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_ADJUSTMENT_NOT_FOUND")
            }

        @Test
        fun `조건이 올바르지 않으면 400이다`() {
            call(HttpMethod.GET, "admin", "$base?status=UNKNOWN").expectStatus().isBadRequest
            call(HttpMethod.GET, "admin", "$base?warehouseId=abc").expectStatus().isBadRequest
        }
    }

    @Nested
    inner class `승인과 거부` {
        @Test
        fun `승인하면 반영된 보정안을 응답하고 승인자와 본사 관리자 여부와 범위를 넘긴다`() =
            runBlocking<Unit> {
                whenever(approveReconciliationUseCase.approve(any())).thenReturn(proposal(AdjustmentStatus.APPLIED))

                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("APPLIED")
                    .jsonPath("$.approvedBy")
                    .isEqualTo("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
                    .consumeWith(ReconciliationApiDocs.approve())

                val command = argumentCaptor<ReviewReconciliationCommand>()
                verifyBlocking(approveReconciliationUseCase) { approve(command.capture()) }
                assertEquals(1L, command.firstValue.stockAdjustmentId)
                assertEquals(true, command.firstValue.isAdmin)
                assertEquals(WarehouseScope.All, command.firstValue.scope)
                assertEquals(false, command.firstValue.reviewer.isBlank())
            }

        @Test
        fun `창고 관리자는 본사 관리자가 아니라고 넘긴다`() =
            runBlocking<Unit> {
                val scope = WarehouseScope.Only(setOf(10L))
                whenever(resolveWarehouseScopeUseCase.resolve(any())).thenReturn(scope)
                whenever(approveReconciliationUseCase.approve(any())).thenReturn(proposal(AdjustmentStatus.APPLIED))

                call(HttpMethod.POST, "warehouse_manager", "$base/{stockAdjustmentId}/approval", 1L).expectStatus().isOk

                val command = argumentCaptor<ReviewReconciliationCommand>()
                verifyBlocking(approveReconciliationUseCase) { approve(command.capture()) }
                assertEquals(false, command.firstValue.isAdmin)
                assertEquals(scope, command.firstValue.scope)
            }

        @Test
        fun `거부하면 거부된 보정안을 응답한다`() =
            runBlocking<Unit> {
                whenever(rejectReconciliationUseCase.reject(any())).thenReturn(proposal(AdjustmentStatus.REJECTED))

                call(HttpMethod.POST, "warehouse_manager", "$base/{stockAdjustmentId}/rejection", 1L)
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("REJECTED")
                    .consumeWith(ReconciliationApiDocs.reject())
            }

        @Test
        fun `서비스 오류는 오류 코드로 응답한다`() =
            runBlocking<Unit> {
                whenever(approveReconciliationUseCase.approve(any()))
                    .thenThrow(AdjustmentNotFoundException())
                    .thenThrow(InvalidAdjustmentStateException())
                    .thenThrow(ReconciliationChangedException())
                    .thenThrow(InsufficientAvailableQuantityException())
                    .thenThrow(ReconciliationAdminRequiredException())
                    .thenThrow(WarehouseAccessDeniedException())
                    .thenThrow(WmsUnavailableException())

                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isNotFound
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_ADJUSTMENT_NOT_FOUND")
                    .consumeWith(ReconciliationApiDocs.notFound())
                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INVALID_ADJUSTMENT_STATE")
                    .consumeWith(ReconciliationApiDocs.conflict())
                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RECONCILIATION_CHANGED")
                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isEqualTo(409)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_INSUFFICIENT_AVAILABLE_QUANTITY")
                call(HttpMethod.POST, "warehouse_manager", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isForbidden
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_RECONCILIATION_ADMIN_REQUIRED")
                    .consumeWith(ReconciliationApiDocs.forbidden())
                call(HttpMethod.POST, "warehouse_manager", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isForbidden
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_WAREHOUSE_ACCESS_DENIED")
                call(HttpMethod.POST, "admin", "$base/{stockAdjustmentId}/approval", 1L)
                    .expectStatus()
                    .isEqualTo(502)
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("INV_WMS_UNAVAILABLE")
                    .consumeWith(ReconciliationApiDocs.wmsUnavailable())
            }
    }

    @Nested
    inner class `권한` {
        @Test
        fun `service role은 403이고 토큰이 없으면 401이며 UseCase를 호출하지 않는다`() =
            runBlocking<Unit> {
                listOf(
                    HttpMethod.GET to base,
                    HttpMethod.GET to "$base/1",
                    HttpMethod.POST to "$base/1/approval",
                    HttpMethod.POST to "$base/1/rejection",
                ).forEach { (method, uri) ->
                    call(method, "service", uri).expectStatus().isForbidden
                    call(method, null, uri).expectStatus().isUnauthorized
                }

                verifyBlocking(searchReconciliationProposalsUseCase, never()) { search(any()) }
                verifyBlocking(getReconciliationProposalUseCase, never()) { getById(any(), any()) }
                verifyBlocking(approveReconciliationUseCase, never()) { approve(any()) }
                verifyBlocking(rejectReconciliationUseCase, never()) { reject(any()) }
            }

        @Test
        fun `소속 사본이 없는 창고 관리자는 범위를 구하는 단계에서 403이다`() =
            runBlocking<Unit> {
                whenever(resolveWarehouseScopeUseCase.resolve(any())).thenThrow(WarehouseAccessDeniedException())

                call(HttpMethod.POST, "warehouse_manager", "$base/{stockAdjustmentId}/approval", 1L).expectStatus().isForbidden

                verifyBlocking(approveReconciliationUseCase, never()) { approve(any()) }
            }
    }
}
