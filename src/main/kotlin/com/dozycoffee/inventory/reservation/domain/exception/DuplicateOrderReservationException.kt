package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class DuplicateOrderReservationException : DomainException(ReservationErrorCode.DUPLICATE_ORDER_RESERVATION)
