package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class ReservationNotFoundException : DomainException(ReservationErrorCode.RESERVATION_NOT_FOUND)
