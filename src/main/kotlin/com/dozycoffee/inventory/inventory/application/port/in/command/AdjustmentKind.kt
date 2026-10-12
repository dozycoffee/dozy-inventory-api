package com.dozycoffee.inventory.inventory.application.port.`in`.command

/** 수량 조정의 출처. 재고 이력의 유형(`ADJUSTMENT` 또는 `RECONCILIATION`)을 정한다. 조정 문서를 모르는 inventory가 다른 도메인에 노출하는 구분이다 */
enum class AdjustmentKind {
    /** WMS 실사 조정(`ADJUSTMENT` 이력) */
    AUDIT,

    /** 대사 보정(`RECONCILIATION` 이력) */
    RECONCILIATION,
}
