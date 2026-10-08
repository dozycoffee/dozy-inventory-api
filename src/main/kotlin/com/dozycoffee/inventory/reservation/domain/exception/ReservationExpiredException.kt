package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 만료 시각이 지난 확정 전 예약은 확정하거나 연장할 수 없다. 만료 스캔이 곧 해제한다 */
class ReservationExpiredException : DomainException(ReservationErrorCode.RESERVATION_EXPIRED)
