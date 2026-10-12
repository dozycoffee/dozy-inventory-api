package com.dozycoffee.inventory.adjustment.domain.enumeration

/** 승인 시점에 다시 확인한 수량으로 보정안을 고친 결과(ADR-0033) */
enum class ReconciliationRevision {
    /** 보정안의 두 수량과 같다 */
    UNCHANGED,

    /** 수량이 달라져 보정안의 수량과 변동량을 새 값으로 바꿨다 */
    UPDATED,

    /** 불일치가 사라져 보정안을 거부 상태로 닫았다 */
    RESOLVED,
}
