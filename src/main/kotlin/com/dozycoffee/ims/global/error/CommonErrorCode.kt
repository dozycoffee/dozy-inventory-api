package com.dozycoffee.ims.global.error

enum class CommonErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    VALIDATION_FAILED(ErrorType.VALIDATION, "VALIDATION_FAILED", "요청 값이 올바르지 않습니다."),
    INTERNAL_ERROR(ErrorType.INTERNAL, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."),
}
