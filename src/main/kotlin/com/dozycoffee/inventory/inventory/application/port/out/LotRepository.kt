package com.dozycoffee.inventory.inventory.application.port.out

import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.model.Lot
import java.time.LocalDate

interface LotRepository {
    /** 식별자가 없으면 저장하고 있으면 갱신한다. 같은 상품에 같은 Lot 번호가 있으면 [com.dozycoffee.inventory.inventory.domain.exception.DuplicateLotException] */
    suspend fun save(lot: Lot): Lot

    suspend fun findById(lotId: Long): Lot?

    /** Lot 번호는 대소문자를 구분해 비교한다 */
    suspend fun findByProductIdAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): Lot?

    /**
     * 유통기한 스캔이 처리할 Lot의 ID를 ID 순서로 최대 [limit]개 반환한다. 저장된 상태가 `NORMAL`인데 유통기한이 [soonLimit] 이내이거나,
     * `EXPIRING_SOON`인데 유통기한이 [today] 이하이거나, `EXPIRED`인데 정상 품질 행에 가용 수량이 남은 Lot이다.
     */
    suspend fun findScanCandidateIds(
        today: LocalDate,
        soonLimit: LocalDate,
        limit: Int,
    ): List<Long>

    /** 저장된 상태가 [expectedStatus]일 때만 [lot]의 상태로 바꾼다. 그 사이 다른 요청이 바꿨으면 아무것도 바꾸지 않고 false를 반환한다 */
    suspend fun updateStatus(
        lot: Lot,
        expectedStatus: LotStatus,
    ): Boolean
}
