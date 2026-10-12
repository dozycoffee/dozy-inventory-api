package com.dozycoffee.inventory.adjustment.domain.enumeration

/** 대사 실행의 상태. 실행 중에 프로세스가 죽으면 `RUNNING`으로 남을 수 있다 */
enum class ReconciliationStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}
