package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.inventory.adapter.`in`.web.response.InventoryHistoryResponse
import com.dozycoffee.inventory.inventory.adapter.`in`.web.response.InventoryResponse
import com.dozycoffee.inventory.inventory.adapter.`in`.web.response.PageResponse
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryHistoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.SearchInventoriesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.SearchInventoriesQuery
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자용 재고 현황·상세·이력 조회(ADR-0030). 본사 관리자는 모든 창고를, 창고 관리자는 배정된 창고만 본다(ADR-0032).
 * 요청자의 창고 범위를 구해 조회에 넘기고, 배정되지 않은 창고는 조회 UseCase가 403으로 거부한다.
 */
@RestController
@RequestMapping("/api/v1/inventories")
class InventoryQueryController(
    private val searchInventoriesUseCase: SearchInventoriesUseCase,
    private val getInventoryUseCase: GetInventoryUseCase,
    private val getInventoryHistoriesUseCase: GetInventoryHistoriesUseCase,
    private val resolveWarehouseScopeUseCase: ResolveWarehouseScopeUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    /** 조건은 모두 선택이고 주어진 조건을 모두 만족하는 재고 행을 준다. 조건 없이 부르면 전체 재고 행을 페이지로 나눠 준다 */
    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @GetMapping
    suspend fun search(
        @RequestParam(required = false) warehouseId: Long?,
        @RequestParam(required = false) productId: Long?,
        @RequestParam(required = false) qualityStatus: QualityStatus?,
        @RequestParam(required = false) lotStatus: LotStatus?,
        @RequestParam(required = false) allocationHold: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<InventoryResponse> =
        PageResponse.from(
            searchInventoriesUseCase.search(
                SearchInventoriesQuery(warehouseId, productId, qualityStatus, lotStatus, allocationHold, page, size, scope()),
            ),
            InventoryResponse::from,
        )

    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @GetMapping("/{inventoryId}")
    suspend fun get(
        @PathVariable inventoryId: Long,
    ): InventoryResponse = InventoryResponse.from(getInventoryUseCase.getById(inventoryId, scope()))

    @PreAuthorize(InventoryAuthorize.ADMIN_OR_WAREHOUSE_MANAGER)
    @GetMapping("/{inventoryId}/histories")
    suspend fun getHistories(
        @PathVariable inventoryId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<InventoryHistoryResponse> =
        PageResponse.from(getInventoryHistoriesUseCase.getHistories(inventoryId, scope(), page, size), InventoryHistoryResponse::from)

    private suspend fun scope(): WarehouseScope = resolveWarehouseScopeUseCase.resolve(currentActorProvider.get())
}
