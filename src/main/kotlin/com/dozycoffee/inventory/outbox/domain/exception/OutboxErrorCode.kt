package com.dozycoffee.inventory.outbox.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class OutboxErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    OUTBOX_EVENT_NOT_FOUND(ErrorType.NOT_FOUND, "INV_OUTBOX_EVENT_NOT_FOUND", "Outbox 이벤트를 찾을 수 없습니다."),
    INVALID_OUTBOX_STATE(ErrorType.CONFLICT, "INV_INVALID_OUTBOX_STATE", "이벤트의 현재 상태에서는 요청한 작업을 할 수 없습니다."),
    INVALID_OUTBOX_QUERY(
        ErrorType.VALIDATION,
        "INV_INVALID_OUTBOX_QUERY",
        "Outbox 이벤트 조회 조건이 올바르지 않습니다. 페이지는 0 이상, 크기는 1 이상 100 이하여야 합니다.",
    ),
    INVALID_OUTBOX_EVENT(
        ErrorType.VALIDATION,
        "INV_INVALID_OUTBOX_EVENT",
        "이벤트의 집계 유형은 정해진 값이고 집계 ID는 양수이며 이벤트 유형·파티션 키는 100자 이하, payload는 비어 있지 않아야 합니다.",
    ),
}
