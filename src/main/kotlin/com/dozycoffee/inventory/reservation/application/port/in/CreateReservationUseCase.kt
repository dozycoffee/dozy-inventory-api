package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult

interface CreateReservationUseCase {
    /**
     * 한 창고에서 주문의 모든 상품을 유통기한이 이른 Lot부터 예약한다. 전체 성공 또는 전체 실패이다.
     * 같은 멱등 키의 재요청은 새로 잡지 않고 저장된 예약을 반환하고, 같은 키에 다른 내용이 오면 거부한다.
     */
    suspend fun create(command: CreateReservationCommand): ReservationResult
}
