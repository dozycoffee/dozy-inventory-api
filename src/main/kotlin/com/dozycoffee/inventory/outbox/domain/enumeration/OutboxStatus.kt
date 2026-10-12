package com.dozycoffee.inventory.outbox.domain.enumeration

enum class OutboxStatus {
    /** 발행 대기 */
    PENDING,

    /** 발행 완료 */
    PUBLISHED,

    /** 이벤트 고유의 이유로 계속 실패해 격리됨. 발행기가 가져가지 않으며 운영자가 확인하고 재발행한다(ADR-0037) */
    DEAD,
}
