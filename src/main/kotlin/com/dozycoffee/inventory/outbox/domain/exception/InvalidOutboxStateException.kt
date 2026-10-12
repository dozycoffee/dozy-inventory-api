package com.dozycoffee.inventory.outbox.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 이벤트의 현재 상태에서는 할 수 없는 전이를 요청했다(예: 발행 대기 이벤트를 재발행) */
class InvalidOutboxStateException : DomainException(OutboxErrorCode.INVALID_OUTBOX_STATE)
