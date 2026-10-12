package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult

interface ReallocateReservationUseCase {
    /**
     * 확정된 예약에서 피킹할 수 없는 Lot의 부족 수량을 같은 창고의 다른 Lot으로 옮기고(FEFO) 부족이 보고된 재고 행을 신규 할당에서 제외한다(ADR-0031).
     * 보류는 재할당보다 먼저 확정하므로 대체 재고가 모자라 재할당이 실패해도 남는다. 같은 멱등 키로 같은 내용을 다시 요청하면 새로 반영하지 않고
     * 처음과 같은 결과를 반환한다.
     */
    suspend fun reallocate(command: ReallocateReservationCommand): ReallocationResult
}
