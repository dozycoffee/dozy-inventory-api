package com.dozycoffee.inventory.reservation.application.port.`in`.result

/** 스캔 한 묶음의 결과. [attempted]는 처리하려 한 Lot 수, [processed]는 성공한 Lot 수, [forceReleased]는 강제 해제한 예약 수다 */
data class LotExpirationScanResult(
    val attempted: Int,
    val processed: Int,
    val forceReleased: Int,
)
