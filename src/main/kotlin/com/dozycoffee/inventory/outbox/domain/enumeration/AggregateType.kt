package com.dozycoffee.inventory.outbox.domain.enumeration

/** 이벤트를 낸 집계. 토픽은 집계마다 하나다(ADR-0025) */
enum class AggregateType {
    INVENTORY,
    RESERVATION,
    LOT,
    PRODUCT,
    ADJUSTMENT,
}
