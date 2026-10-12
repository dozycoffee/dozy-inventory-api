package com.dozycoffee.inventory.inventory.application.port.`in`

interface FindLotsToScanUseCase {
    /**
     * 유통기한 스캔이 처리할 Lot의 ID를 ID 순서로 최대 [limit]개 반환한다(ADR-0027). 저장된 상태가 바뀌어야 하는 Lot과,
     * 이미 경과했지만 정상 품질 행에 가용 수량이 남은 Lot이다.
     */
    suspend fun findLotIds(limit: Int): List<Long>
}
