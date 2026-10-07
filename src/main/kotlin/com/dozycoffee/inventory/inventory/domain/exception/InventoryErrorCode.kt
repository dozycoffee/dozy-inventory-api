package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class InventoryErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_LOT_NUMBER(ErrorType.VALIDATION, "IMS_INVALID_LOT_NUMBER", "Lot 번호는 비어 있을 수 없습니다."),
    INVALID_LOT_DATES(ErrorType.VALIDATION, "IMS_INVALID_LOT_DATES", "유통기한은 제조일자보다 빠를 수 없습니다."),
    INVALID_EXPIRING_SOON_DAYS(ErrorType.VALIDATION, "IMS_INVALID_EXPIRING_SOON_DAYS", "임박 기준 일수는 0 이상이어야 합니다."),
    INVALID_QUANTITY(ErrorType.VALIDATION, "IMS_INVALID_QUANTITY", "수량은 1 이상이어야 합니다."),
    QUANTITY_OVERFLOW(ErrorType.VALIDATION, "IMS_QUANTITY_OVERFLOW", "총 수량이 허용 범위를 넘습니다."),
    INVALID_HOLD_REASON(ErrorType.VALIDATION, "IMS_INVALID_HOLD_REASON", "보류 사유는 비어 있을 수 없고 100자를 넘을 수 없습니다."),
    INVALID_IDEMPOTENCY_KEY(
        ErrorType.VALIDATION,
        "IMS_INVALID_IDEMPOTENCY_KEY",
        "멱등 키는 비어 있지 않은 ASCII 문자열이며 100자를 넘을 수 없습니다.",
    ),
    INVALID_REQUESTER_SERVICE(
        ErrorType.VALIDATION,
        "IMS_INVALID_REQUESTER_SERVICE",
        "요청 서비스는 비어 있을 수 없고 50자를 넘을 수 없습니다.",
    ),
    INVALID_HISTORY_CHANGE(ErrorType.VALIDATION, "IMS_INVALID_HISTORY_CHANGE", "변동량은 0일 수 없고 이력 유형의 부호와 맞아야 합니다."),
    INVALID_HISTORY_AFTER(ErrorType.VALIDATION, "IMS_INVALID_HISTORY_AFTER", "변경 후 수량은 0 이상이어야 합니다."),
    INVENTORY_NOT_FOUND(ErrorType.NOT_FOUND, "IMS_INVENTORY_NOT_FOUND", "재고를 찾을 수 없습니다."),
    DUPLICATE_LOT(ErrorType.CONFLICT, "IMS_DUPLICATE_LOT", "이미 등록된 Lot 번호입니다."),
    DUPLICATE_IDEMPOTENCY_KEY(ErrorType.CONFLICT, "IMS_DUPLICATE_IDEMPOTENCY_KEY", "이미 처리한 멱등 키입니다."),
    INVALID_INBOUND_QUALITY_STATUS(
        ErrorType.VALIDATION,
        "IMS_INVALID_INBOUND_QUALITY_STATUS",
        "입고 품질 상태는 NORMAL 또는 DEFECTIVE여야 합니다.",
    ),
    LOT_MISMATCH(ErrorType.CONFLICT, "IMS_LOT_MISMATCH", "이미 등록된 Lot과 제조일자 또는 유통기한이 다릅니다."),
    IDEMPOTENCY_KEY_CONFLICT(ErrorType.CONFLICT, "IMS_IDEMPOTENCY_KEY_CONFLICT", "이미 처리한 멱등 키가 다른 내용으로 다시 요청되었습니다."),
    INSUFFICIENT_AVAILABLE_QUANTITY(ErrorType.CONFLICT, "IMS_INSUFFICIENT_AVAILABLE_QUANTITY", "가용 수량이 부족합니다."),
    INSUFFICIENT_RESERVED_QUANTITY(ErrorType.CONFLICT, "IMS_INSUFFICIENT_RESERVED_QUANTITY", "예약 수량이 부족합니다."),
    INVENTORY_NOT_RESERVABLE(ErrorType.CONFLICT, "IMS_INVENTORY_NOT_RESERVABLE", "정상 품질 상태의 재고만 예약할 수 있습니다."),
    ALLOCATION_HELD(ErrorType.CONFLICT, "IMS_ALLOCATION_HELD", "할당이 보류된 재고입니다."),
}
