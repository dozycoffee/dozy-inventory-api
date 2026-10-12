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
    ADJUSTMENT_NOT_FOUND(ErrorType.NOT_FOUND, "INV_ADJUSTMENT_NOT_FOUND", "조정(보정안)을 찾을 수 없습니다."),
    INVALID_ADJUSTMENT_STATE(
        ErrorType.CONFLICT,
        "INV_INVALID_ADJUSTMENT_STATE",
        "조정의 현재 상태에서는 요청한 작업을 할 수 없습니다.",
    ),
    RECONCILIATION_CHANGED(
        ErrorType.CONFLICT,
        "INV_RECONCILIATION_CHANGED",
        "보정안을 만든 뒤 수량이 바뀌어 보정안을 다시 계산했습니다. 새 수량을 확인하고 다시 승인해 주세요.",
    ),
    RECONCILIATION_ADMIN_REQUIRED(
        ErrorType.FORBIDDEN,
        "INV_RECONCILIATION_ADMIN_REQUIRED",
        "변동량이 승인 임계치를 넘는 보정안은 본사 관리자만 승인할 수 있습니다.",
    ),
    INVALID_RECONCILIATION(
        ErrorType.VALIDATION,
        "INV_INVALID_RECONCILIATION",
        "대사 보정안은 창고와 대사 실행이 양수이고 두 수량이 0 이상이며 서로 달라야 합니다.",
    ),
    WMS_UNAVAILABLE(
        ErrorType.UPSTREAM,
        "INV_WMS_UNAVAILABLE",
        "WMS에서 창고 수량을 조회하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
    ),
    DUPLICATE_ADJUSTMENT_KEY(ErrorType.CONFLICT, "INV_DUPLICATE_ADJUSTMENT_KEY", "이미 처리한 조정의 멱등 키입니다."),
}
