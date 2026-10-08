package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.reservation.adapter.`in`.web.request.ExtendReservationRequest
import com.dozycoffee.inventory.reservation.adapter.`in`.web.response.ReservationResponse
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExtendReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * 예약의 확정·해제·연장. 예약 ID가 대상을 식별하므로 `Idempotency-Key` 없이 상태로 멱등하다(ADR-0023):
 * 이미 목표 상태이면 아무것도 바꾸지 않고 200으로 현재 상태를 반환한다. 만료는 스케줄러가 맡아 API가 없다.
 */
@RestController
@RequestMapping("/api/v1/reservations/{reservationId}")
class ReservationTransitionController(
    private val confirmReservationUseCase: ConfirmReservationUseCase,
    private val releaseReservationUseCase: ReleaseReservationUseCase,
    private val extendReservationUseCase: ExtendReservationUseCase,
    private val clock: Clock,
) {
    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping("/confirm")
    suspend fun confirm(
        @PathVariable reservationId: Long,
    ): ReservationResponse = ReservationResponse.from(confirmReservationUseCase.confirm(reservationId), clock.zone)

    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping("/release")
    suspend fun release(
        @PathVariable reservationId: Long,
    ): ReservationResponse = ReservationResponse.from(releaseReservationUseCase.release(reservationId), clock.zone)

    @PreAuthorize(InventoryAuthorize.SERVICE)
    @PostMapping("/extend")
    suspend fun extend(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: ExtendReservationRequest,
    ): ReservationResponse =
        ReservationResponse.from(extendReservationUseCase.extend(request.toCommand(reservationId, clock.zone)), clock.zone)
}
