package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.inventory.adjustment.adapter.`in`.web.request.RequestStockAdjustmentRequest
import com.dozycoffee.inventory.adjustment.adapter.`in`.web.response.StockAdjustmentResponse
import com.dozycoffee.inventory.adjustment.application.port.`in`.RequestStockAdjustmentUseCase
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/stock-adjustments")
class StockAdjustmentController(
    private val requestStockAdjustmentUseCase: RequestStockAdjustmentUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    /** 요청 주체는 토큰의 principalId(system client)이고 `local` 프로필에서는 개발 사용자다. 처음 반영과 재요청 모두 200이다 */
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping
    suspend fun request(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: RequestStockAdjustmentRequest,
    ): StockAdjustmentResponse {
        val requester: String = currentActorProvider.get().auditName
        return StockAdjustmentResponse.from(requestStockAdjustmentUseCase.request(request.toCommand(idempotencyKey, requester)))
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
