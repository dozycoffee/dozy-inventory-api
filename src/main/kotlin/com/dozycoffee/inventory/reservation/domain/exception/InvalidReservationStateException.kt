package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 예약의 현재 상태에서는 할 수 없는 전이를 요청했다(예: 해제된 예약을 확정) */
class InvalidReservationStateException : DomainException(ReservationErrorCode.INVALID_RESERVATION_STATE)
