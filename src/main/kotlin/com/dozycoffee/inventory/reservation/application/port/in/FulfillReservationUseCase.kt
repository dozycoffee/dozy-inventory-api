package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult

interface FulfillReservationUseCase {
    /**
     * 확정된 예약의 출고를 확정한다. 출고한 수량만큼 총 수량과 예약 수량을 함께 줄이고, 결품(할당 − 출고)은 예약 수량만 되돌리며
     * 예약은 `FULFILLED`가 된다. 같은 멱등 키로 같은 수량을 다시 요청하면 새로 반영하지 않고 처음과 같은 결과를 반환한다.
     */
    suspend fun fulfill(command: FulfillReservationCommand): FulfillmentResult
}
