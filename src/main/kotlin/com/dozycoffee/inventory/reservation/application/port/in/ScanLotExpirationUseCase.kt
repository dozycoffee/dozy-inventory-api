package com.dozycoffee.inventory.reservation.application.port.`in`

import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult

interface ScanLotExpirationUseCase {
    /**
     * 유통기한 스캔 한 묶음을 처리한다(ADR-0027). 처리할 Lot을 최대 [limit]개 가져와 Lot마다 차례로
     * ① 예약 수량을 잡은 경과 Lot의 확정 전 예약을 강제 해제하고 ② 건너뛸 확정 예약을 찾고
     * ③ Lot 상태 갱신과 품질 전환, `Lot 경과` 이벤트 저장을 한다. Lot 하나가 실패해도 로그만 남기고 나머지를 계속 처리한다.
     * 여러 번, 여러 인스턴스에서 호출해도 안전하다(각 단계가 멱등이거나 조건부).
     */
    suspend fun scan(limit: Int): LotExpirationScanResult
}
