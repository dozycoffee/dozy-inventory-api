package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.inventory.adapter.`in`.web.request.ConfirmInboundReceiptRequest
import com.dozycoffee.inventory.inventory.adapter.`in`.web.response.InboundReceiptResponse
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmInboundUseCase
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/inbound-receipts")
class InboundReceiptController(
    private val confirmInboundUseCase: ConfirmInboundUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    /** 요청 주체는 토큰의 principalId(system client)이고 `local` 프로필에서는 개발 사용자다 */
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping
    suspend fun confirm(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: ConfirmInboundReceiptRequest,
    ): InboundReceiptResponse {
        val requester: String = currentActorProvider.get().auditName
        return InboundReceiptResponse.from(confirmInboundUseCase.confirm(request.toCommand(idempotencyKey, requester)))
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
