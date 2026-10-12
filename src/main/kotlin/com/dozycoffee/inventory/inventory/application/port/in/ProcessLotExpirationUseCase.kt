package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotExpirationResult

interface ProcessLotExpirationUseCase {
    /**
     * Lot 하나의 상태를 오늘 기준으로 갱신하고, 경과했으면 정상 품질 행의 가용 수량을 폐기 예정 행으로 전환한다(ADR-0027).
     * 상태 갱신, 전환, `QUALITY_TRANSFER` 이력을 한 트랜잭션으로 처리하며 여러 번 호출해도 같은 결과다.
     * 같은 날 이미 처리한 Lot이거나 다른 인스턴스가 먼저 처리했으면 변화 없는 결과를 반환한다.
     * 이번에 경과로 바뀌었거나 수량을 전환했으면 `Lot 경과` 이벤트를 같은 트랜잭션으로 Outbox에 저장한다.
     * [confirmedReservationIds]는 수량을 잡고 있어 건너뛴 확정 예약의 ID이며 이벤트에 담긴다(없으면 빈 목록).
     */
    suspend fun process(
        lotId: Long,
        confirmedReservationIds: List<Long>,
    ): LotExpirationResult
}
