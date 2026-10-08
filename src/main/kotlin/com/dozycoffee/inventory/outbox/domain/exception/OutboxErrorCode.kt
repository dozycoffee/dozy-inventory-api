package com.dozycoffee.inventory.outbox.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class OutboxErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_OUTBOX_EVENT(
        ErrorType.VALIDATION,
        "INV_INVALID_OUTBOX_EVENT",
        "이벤트의 집계 유형은 정해진 값이고 집계 ID는 양수이며 이벤트 유형·파티션 키는 100자 이하, payload는 비어 있지 않아야 합니다.",
    ),
}
