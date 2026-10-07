package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class DuplicateReservationKeyException : DomainException(ReservationErrorCode.DUPLICATE_RESERVATION_KEY)
