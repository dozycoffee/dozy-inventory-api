package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class ProductNotReservableException : DomainException(ReservationErrorCode.PRODUCT_NOT_RESERVABLE)
