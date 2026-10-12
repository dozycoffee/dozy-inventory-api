package com.dozycoffee.inventory.access.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class AccessErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_PRINCIPAL_ID(ErrorType.VALIDATION, "INV_INVALID_PRINCIPAL_ID", "직원 ID는 UUID 형식이어야 합니다."),
    INVALID_WAREHOUSE_ACCESS(
        ErrorType.VALIDATION,
        "INV_INVALID_WAREHOUSE_ACCESS",
        "소속 창고는 양수이고 중복 없이 1000개 이하여야 합니다.",
    ),
}
