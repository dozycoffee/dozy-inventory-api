package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class InventoryErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_LOT_NUMBER(ErrorType.VALIDATION, "INV_INVALID_LOT_NUMBER", "Lot 번호는 비어 있을 수 없습니다."),
    INVALID_LOT_DATES(ErrorType.VALIDATION, "INV_INVALID_LOT_DATES", "유통기한은 제조일자보다 빠를 수 없습니다."),
    INVALID_EXPIRING_SOON_DAYS(ErrorType.VALIDATION, "INV_INVALID_EXPIRING_SOON_DAYS", "임박 기준 일수는 0 이상이어야 합니다."),
    INVALID_QUANTITY(ErrorType.VALIDATION, "INV_INVALID_QUANTITY", "수량은 1 이상이어야 합니다."),
    QUANTITY_OVERFLOW(ErrorType.VALIDATION, "INV_QUANTITY_OVERFLOW", "총 수량이 허용 범위를 넘습니다."),
    INVALID_HOLD_REASON(ErrorType.VALIDATION, "INV_INVALID_HOLD_REASON", "보류 사유는 비어 있을 수 없고 100자를 넘을 수 없습니다."),
    INVALID_HISTORY_CHANGE(ErrorType.VALIDATION, "INV_INVALID_HISTORY_CHANGE", "변동량은 0일 수 없고 이력 유형의 부호와 맞아야 합니다."),
    INVALID_HISTORY_AFTER(ErrorType.VALIDATION, "INV_INVALID_HISTORY_AFTER", "변경 후 수량은 0 이상이어야 합니다."),
    INVENTORY_NOT_FOUND(ErrorType.NOT_FOUND, "INV_INVENTORY_NOT_FOUND", "재고를 찾을 수 없습니다."),
    DUPLICATE_LOT(ErrorType.CONFLICT, "INV_DUPLICATE_LOT", "이미 등록된 Lot 번호입니다."),
    DUPLICATE_IDEMPOTENCY_KEY(ErrorType.CONFLICT, "INV_DUPLICATE_IDEMPOTENCY_KEY", "이미 처리한 멱등 키입니다."),
    INVALID_INBOUND_QUALITY_STATUS(
        ErrorType.VALIDATION,
        "INV_INVALID_INBOUND_QUALITY_STATUS",
        "입고 품질 상태는 NORMAL 또는 DEFECTIVE여야 합니다.",
    ),
    LOT_MISMATCH(ErrorType.CONFLICT, "INV_LOT_MISMATCH", "이미 등록된 Lot과 제조일자 또는 유통기한이 다릅니다."),
    INVALID_ALLOCATION_REQUEST(
        ErrorType.VALIDATION,
        "INV_INVALID_ALLOCATION_REQUEST",
        "창고 ID는 양수이고 상품은 1개 이상 100개 이하이며 상품 ID는 양수이고 중복이 없어야 하며 수량은 1 이상이어야 합니다.",
    ),
    INVALID_SHIP_REQUEST(
        ErrorType.VALIDATION,
        "INV_INVALID_SHIP_REQUEST",
        "출고할 재고 행은 1개 이상이고 ID는 양수이며 중복이 없어야 하고 행마다 출고 수량과 되돌릴 수량은 0 이상이며 합이 1 이상이어야 합니다.",
    ),
    INVALID_RELEASE_REQUEST(
        ErrorType.VALIDATION,
        "INV_INVALID_RELEASE_REQUEST",
        "되돌릴 재고 행은 1개 이상이고 ID는 양수이며 중복이 없어야 하고 수량은 1 이상이어야 합니다.",
    ),
    INVALID_AVAILABILITY_QUERY(
        ErrorType.VALIDATION,
        "INV_INVALID_AVAILABILITY_QUERY",
        "상품은 1개 이상 100개 이하이고 창고는 지정하면 1개 이상 100개 이하이며 ID는 모두 양수여야 합니다.",
    ),
    INSUFFICIENT_AVAILABLE_QUANTITY(ErrorType.CONFLICT, "INV_INSUFFICIENT_AVAILABLE_QUANTITY", "가용 수량이 부족합니다."),
    INSUFFICIENT_RESERVED_QUANTITY(ErrorType.CONFLICT, "INV_INSUFFICIENT_RESERVED_QUANTITY", "예약 수량이 부족합니다."),
    INVENTORY_NOT_RESERVABLE(ErrorType.CONFLICT, "INV_INVENTORY_NOT_RESERVABLE", "정상 품질 상태의 재고만 예약할 수 있습니다."),
    ALLOCATION_HELD(ErrorType.CONFLICT, "INV_ALLOCATION_HELD", "할당이 보류된 재고입니다."),
}
