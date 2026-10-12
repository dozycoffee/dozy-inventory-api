package com.dozycoffee.inventory.adjustment.application.port.out

import com.dozycoffee.inventory.adjustment.domain.model.ReconciliationRun

/** 대사 실행 기록을 저장한다. 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface ReconciliationRunRepository {
    /** 새 실행을 저장한다 */
    suspend fun save(run: ReconciliationRun): ReconciliationRun

    suspend fun findById(reconciliationRunId: Long): ReconciliationRun?

    /** 끝난 실행의 상태, 종료 시각, 비교한 항목 수, 불일치 수를 저장한다 */
    suspend fun update(run: ReconciliationRun)
}
