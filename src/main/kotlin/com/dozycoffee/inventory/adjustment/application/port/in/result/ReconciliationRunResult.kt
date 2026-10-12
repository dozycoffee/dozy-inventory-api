package com.dozycoffee.inventory.adjustment.application.port.`in`.result

import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationStatus

/**
 * 창고 한 곳의 대사 실행 결과. [checkedCount]는 비교한 항목 수, [mismatchCount]는 불일치 수, [createdCount]는 새로 만든 보정안 수이다
 * (이미 처리 대기 중이거나 같은 날 만든 보정안은 새로 만들지 않아 불일치 수보다 적을 수 있다). 실패하면 수는 0이다.
 */
data class ReconciliationRunResult(
    val warehouseId: Long,
    val reconciliationRunId: Long,
    val status: ReconciliationStatus,
    val checkedCount: Int,
    val mismatchCount: Int,
    val createdCount: Int,
)
