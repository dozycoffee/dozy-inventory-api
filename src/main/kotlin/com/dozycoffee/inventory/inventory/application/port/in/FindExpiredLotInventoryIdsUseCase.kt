package com.dozycoffee.inventory.inventory.application.port.`in`

interface FindExpiredLotInventoryIdsUseCase {
    /**
     * 유통기한이 오늘 이하인 Lot의 정상 품질 재고 행 중 예약 수량이 있는 행의 ID를 반환한다. Lot이 아직 경과 전이거나 유통기한이 없거나
     * 해당하는 행이 없으면 빈 목록이다. 저장된 Lot 상태가 아니라 유통기한 날짜로 판단하므로 상태 갱신 전에도 쓸 수 있다.
     * 예약을 강제 해제하려는 쪽이 대상 예약을 찾는 데 쓴다.
     */
    suspend fun findReservedInventoryIds(lotId: Long): List<Long>
}
