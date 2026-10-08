package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 읽은 뒤 갱신하기 전에 다른 요청이 예약 상태를 바꿨다. 서비스가 새 트랜잭션에서 다시 읽어 판정하며, 계속 밀리면 호출자에게 알린다 */
class ReservationChangedConcurrentlyException : DomainException(ReservationErrorCode.RESERVATION_CHANGED_CONCURRENTLY)
