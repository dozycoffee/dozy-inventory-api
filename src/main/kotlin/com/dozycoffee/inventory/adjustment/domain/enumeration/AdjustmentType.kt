package com.dozycoffee.inventory.adjustment.domain.enumeration

enum class AdjustmentType {
    /** WMS 실사 조정. 요청 즉시 반영한다 */
    AUDIT,

    /** 대사 보정. 항목별로 승인한 뒤 반영한다 */
    RECONCILIATION,
}
