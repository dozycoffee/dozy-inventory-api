package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.adjustment.adapter.`in`.web.response.ReconciliationProposalPageResponse
import com.dozycoffee.inventory.adjustment.adapter.`in`.web.response.ReconciliationProposalResponse
import com.dozycoffee.inventory.adjustment.application.port.`in`.ApproveReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.GetReconciliationProposalUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.RejectReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.SearchReconciliationProposalsUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.SearchReconciliationProposalsQuery
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.security.Actor
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.global.security.InventoryRole
import com.dozycoffee.inventory.global.security.UserActor
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 대사 보정안 조회·승인·거부(ADR-0033). 본사 관리자는 모든 창고를, 창고 관리자는 배정된 창고의 보정안만 다루고(ADR-0032) 변동량이 임계치를 넘는
 * 보정안의 승인은 본사 관리자만 할 수 있다. 승인·거부는 보정안 ID가 식별자이고 상태로 멱등해 `Idempotency-Key`를 받지 않는다.
 * 승인자는 토큰의 principalId이다.
 */
@RestController
@RequestMapping("/api/v1/reconciliations/proposals")
class ReconciliationController(
    private val searchReconciliationProposalsUseCase: SearchReconciliationProposalsUseCase,
    private val getReconciliationProposalUseCase: GetReconciliationProposalUseCase,
    private val approveReconciliationUseCase: ApproveReconciliationUseCase,
    private val rejectReconciliationUseCase: RejectReconciliationUseCase,
    private val resolveWarehouseScopeUseCase: ResolveWarehouseScopeUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @GetMapping
    suspend fun search(
        @RequestParam(required = false) warehouseId: Long?,
        @RequestParam(required = false) status: AdjustmentStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ReconciliationProposalPageResponse =
        ReconciliationProposalPageResponse.from(
            searchReconciliationProposalsUseCase.search(
                SearchReconciliationProposalsQuery(warehouseId, status, page, size, scopeOf(currentActorProvider.get())),
            ),
        )

    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @GetMapping("/{stockAdjustmentId}")
    suspend fun get(
        @PathVariable stockAdjustmentId: Long,
    ): ReconciliationProposalResponse =
        ReconciliationProposalResponse.from(
            getReconciliationProposalUseCase.getById(stockAdjustmentId, scopeOf(currentActorProvider.get())),
        )

    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @PostMapping("/{stockAdjustmentId}/approval")
    suspend fun approve(
        @PathVariable stockAdjustmentId: Long,
    ): ReconciliationProposalResponse =
        ReconciliationProposalResponse.from(approveReconciliationUseCase.approve(reviewOf(stockAdjustmentId, currentActorProvider.get())))

    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @PostMapping("/{stockAdjustmentId}/rejection")
    suspend fun reject(
        @PathVariable stockAdjustmentId: Long,
    ): ReconciliationProposalResponse =
        ReconciliationProposalResponse.from(rejectReconciliationUseCase.reject(reviewOf(stockAdjustmentId, currentActorProvider.get())))

    private suspend fun scopeOf(actor: Actor): WarehouseScope = resolveWarehouseScopeUseCase.resolve(actor)

    private suspend fun reviewOf(
        stockAdjustmentId: Long,
        actor: Actor,
    ): ReviewReconciliationCommand =
        ReviewReconciliationCommand(
            stockAdjustmentId = stockAdjustmentId,
            reviewer = actor.auditName,
            isAdmin = (actor as? UserActor)?.roles?.contains(InventoryRole.ADMIN.code) == true,
            scope = scopeOf(actor),
        )
}
