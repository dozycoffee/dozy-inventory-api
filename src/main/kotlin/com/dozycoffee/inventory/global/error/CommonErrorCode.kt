package com.dozycoffee.inventory.global.error

enum class CommonErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    VALIDATION_FAILED(ErrorType.VALIDATION, "VALIDATION_FAILED", "요청 값이 올바르지 않습니다."),
    INVALID_IDEMPOTENCY_KEY(
        ErrorType.VALIDATION,
        "INV_INVALID_IDEMPOTENCY_KEY",
        "멱등 키는 비어 있지 않은 ASCII 문자열이며 100자를 넘을 수 없습니다.",
    ),
    INVALID_REQUESTER_SERVICE(
        ErrorType.VALIDATION,
        "INV_INVALID_REQUESTER_SERVICE",
        "요청 서비스는 비어 있을 수 없고 50자를 넘을 수 없습니다.",
    ),
    INTERNAL_ERROR(ErrorType.INTERNAL, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."),
}
