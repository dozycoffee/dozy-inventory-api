package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.global.domain.QualityStatus
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

/** 관리자용 재고 현황·상세·이력 조회(ADR-0030). 창고 관리자는 창고 접근 제어(F-021) 이후에 연다 */
@RestController
@RequestMapping("/api/v1/inventories")
class InventoryQueryController(
    private val searchInventoriesUseCase: SearchInventoriesUseCase,
    private val getInventoryUseCase: GetInventoryUseCase,
    private val getInventoryHistoriesUseCase: GetInventoryHistoriesUseCase,
) {
    /** 조건은 모두 선택이고 주어진 조건을 모두 만족하는 재고 행을 준다. 조건 없이 부르면 전체 재고 행을 페이지로 나눠 준다 */
    @PreAuthorize(InventoryAuthorize.ADMIN)
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
                SearchInventoriesQuery(warehouseId, productId, qualityStatus, lotStatus, allocationHold, page, size),
            ),
            InventoryResponse::from,
        )

    @PreAuthorize(InventoryAuthorize.ADMIN)
    @GetMapping("/{inventoryId}")
    suspend fun get(
        @PathVariable inventoryId: Long,
    ): InventoryResponse = InventoryResponse.from(getInventoryUseCase.getById(inventoryId))

    @PreAuthorize(InventoryAuthorize.ADMIN)
    @GetMapping("/{inventoryId}/histories")
    suspend fun getHistories(
        @PathVariable inventoryId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<InventoryHistoryResponse> =
        PageResponse.from(getInventoryHistoriesUseCase.getHistories(inventoryId, page, size), InventoryHistoryResponse::from)
}
