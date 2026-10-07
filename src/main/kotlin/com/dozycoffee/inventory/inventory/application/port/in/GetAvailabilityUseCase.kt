package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.command.GetAvailabilityQuery
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AvailabilityResult

interface GetAvailabilityUseCase {
    /** 상품별 가용 재고를 창고별로 나눠 조회한다. 조회 결과는 약속이 아니며 약속은 예약으로만 한다 */
    suspend fun getAvailability(query: GetAvailabilityQuery): AvailabilityResult
}
