package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.inventory.adapter.`in`.web.response.AvailabilityResponse
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAvailabilityUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.GetAvailabilityQuery
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/inventories")
class InventoryAvailabilityController(
    private val getAvailabilityUseCase: GetAvailabilityUseCase,
    private val resolveWarehouseScopeUseCase: ResolveWarehouseScopeUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    /**
     * `productIds`와 `warehouseIds`는 쉼표로 구분한다(`1,2,3`). 반복해서(`productIds=1&productIds=2`) 보내도 받는다.
     * 창고 관리자는 `warehouseIds`를 생략하면 배정된 창고만 조회되고 배정되지 않은 창고를 지정하면 403이다(ADR-0032).
     */
    @PreAuthorize(InventoryAuthorize.ANY)
    @GetMapping("/availability")
    suspend fun getAvailability(
        @RequestParam productIds: List<Long>,
        @RequestParam(required = false) warehouseIds: List<Long>?,
    ): AvailabilityResponse =
        AvailabilityResponse.from(
            getAvailabilityUseCase.getAvailability(
                GetAvailabilityQuery(
                    productIds.toSet(),
                    warehouseIds?.toSet(),
                    resolveWarehouseScopeUseCase.resolve(currentActorProvider.get()),
                ),
            ),
        )
}
