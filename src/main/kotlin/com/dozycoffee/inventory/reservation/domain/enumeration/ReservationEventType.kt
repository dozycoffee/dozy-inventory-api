package com.dozycoffee.inventory.reservation.domain.enumeration

enum class ReservationEventType {
    CREATED,
    EXTENDED,
    CONFIRMED,
    RELEASED,
    EXPIRED,
    FORCE_RELEASED,
    REALLOCATED,
    FULFILLED,
}
