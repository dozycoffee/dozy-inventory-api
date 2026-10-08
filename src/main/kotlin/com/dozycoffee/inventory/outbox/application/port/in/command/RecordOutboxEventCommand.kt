package com.dozycoffee.inventory.outbox.application.port.`in`.command

/**
 * 저장할 이벤트. [aggregateType]은 `INVENTORY`, `RESERVATION`, `LOT`, `PRODUCT`, `ADJUSTMENT` 중 하나이고 [payload]는 JSON 문자열이다.
 * 다른 도메인이 `outbox`의 도메인 모델을 import하지 않도록 집계 유형은 문자열로 받는다.
 */
data class RecordOutboxEventCommand(
    val aggregateType: String,
    val aggregateId: Long,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
)
