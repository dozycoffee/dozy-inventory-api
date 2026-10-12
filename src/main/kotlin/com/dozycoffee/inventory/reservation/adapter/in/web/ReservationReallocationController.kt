package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.reservation.adapter.`in`.web.request.ReallocateReservationRequest
import com.dozycoffee.inventory.reservation.adapter.`in`.web.response.ReallocationResponse
import com.dozycoffee.inventory.reservation.application.port.`in`.ReallocateReservationUseCase
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** WMS의 Lot 재할당 요청. 수량을 바꾸는 요청이라 `Idempotency-Key`를 받고, 처음과 재요청 모두 200이다(ADR-0019, ADR-0031) */
@RestController
@RequestMapping("/api/v1/reservations/{reservationId}")
class ReservationReallocationController(
    private val reallocateReservationUseCase: ReallocateReservationUseCase,
) {
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping("/reallocation")
    suspend fun reallocate(
        @PathVariable reservationId: Long,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @Valid @RequestBody request: ReallocateReservationRequest,
    ): ReallocationResponse =
        ReallocationResponse.from(reallocateReservationUseCase.reallocate(request.toCommand(reservationId, idempotencyKey)))

    private companion object {
        const val IDEMPOTENCY_KEY_HEADER: String = "Idempotency-Key"
    }
}
