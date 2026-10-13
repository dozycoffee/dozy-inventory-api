package com.dozycoffee.inventory.outbox.application.port.`in`.command

/**
 * 저장할 이벤트. [aggregateType]은 `INVENTORY`, `RESERVATION`, `LOT`, `PRODUCT`, `ADJUSTMENT` 중 하나이고 [payload]는 도메인 본문(JSON 객체 문자열)이고 저장할 때 서비스가 공통 형식의 `data`로 감싼다.
 * [eventVersion]은 payload 스키마 버전이다(호환되지 않는 변경에만 올린다, ADR-0038). 이벤트 ID와 추적 컨텍스트는 저장할 때 서비스가 채운다.
 * 다른 도메인이 `outbox`의 도메인 모델을 import하지 않도록 집계 유형은 문자열로 받는다.
 */
data class RecordOutboxEventCommand(
    val aggregateType: String,
    val aggregateId: Long,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
    val eventVersion: Int = 1,
)
