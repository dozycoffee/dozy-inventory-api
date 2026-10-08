package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.reservation.adapter.`in`.web.request.FulfillReservationRequest
import com.dozycoffee.inventory.reservation.adapter.`in`.web.response.FulfillmentResponse
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** WMS의 출고 확정. 수량을 바꾸는 요청이라 `Idempotency-Key`를 받고, 처음과 재요청 모두 200이다(ADR-0019, ADR-0024) */
@RestController
@RequestMapping("/api/v1/reservations/{reservationId}")
class ReservationFulfillmentController(
    private val fulfillReservationUseCase: FulfillReservationUseCase,
    private val currentActorProvider: CurrentActorProvider,
) {
    /** 요청 주체는 토큰의 principalId(system client)이고 `local` 프로필에서는 개발 사용자다 */
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping("/fulfillment")
    suspend fun fulfill(
        @PathVariable reservationId: Long,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: FulfillReservationRequest,
    ): FulfillmentResponse {
        val requester: String = currentActorProvider.get().auditName
        return FulfillmentResponse.from(fulfillReservationUseCase.fulfill(request.toCommand(reservationId, idempotencyKey, requester)))
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
