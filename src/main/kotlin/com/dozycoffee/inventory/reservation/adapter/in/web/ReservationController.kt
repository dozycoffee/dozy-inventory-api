package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.reservation.adapter.`in`.web.request.CreateReservationRequest
import com.dozycoffee.inventory.reservation.adapter.`in`.web.response.ReservationResponse
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController(
    private val createReservationUseCase: CreateReservationUseCase,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) {
    /** 요청 주체는 토큰의 principalId(system client)이고 `local` 프로필에서는 개발 사용자다. 처음과 재요청 모두 200이다(ADR-0019) */
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping
    suspend fun create(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: CreateReservationRequest,
    ): ReservationResponse {
        val requester: String = currentActorProvider.get().auditName
        return ReservationResponse.from(
            createReservationUseCase.create(request.toCommand(idempotencyKey, requester, clock.zone)),
            clock.zone,
        )
    }

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
