package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class AdjustmentErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_STOCK_ADJUSTMENT(
        ErrorType.VALIDATION,
        "INV_INVALID_STOCK_ADJUSTMENT",
        "조정은 창고와 실사 건 ID가 양수이고 항목이 1개 이상 500개 이하이며 변동량이 0이 아니고 같은 Lot과 품질 상태가 중복되면 안 됩니다.",
    ),
    INVALID_APPROVER(ErrorType.VALIDATION, "INV_INVALID_APPROVER", "승인자는 비어 있을 수 없고 100자를 넘을 수 없습니다."),
    APPROVAL_REQUIRED(
        ErrorType.VALIDATION,
        "INV_APPROVAL_REQUIRED",
        "변동량이 승인 임계치를 넘는 항목이 있어 승인자가 필요합니다.",
    ),
    DUPLICATE_ADJUSTMENT_KEY(ErrorType.CONFLICT, "INV_DUPLICATE_ADJUSTMENT_KEY", "이미 처리한 조정의 멱등 키입니다."),
}
