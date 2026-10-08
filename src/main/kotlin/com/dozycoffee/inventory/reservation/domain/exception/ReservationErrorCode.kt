package com.dozycoffee.inventory.reservation.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class ReservationErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_RESERVATION_WAREHOUSE(ErrorType.VALIDATION, "INV_INVALID_RESERVATION_WAREHOUSE", "창고 ID는 양수여야 합니다."),
    INVALID_RESERVATION_CHANNEL(ErrorType.VALIDATION, "INV_INVALID_RESERVATION_CHANNEL", "채널은 비어 있을 수 없고 50자를 넘을 수 없습니다."),
    INVALID_EXTERNAL_ORDER_ID(ErrorType.VALIDATION, "INV_INVALID_EXTERNAL_ORDER_ID", "주문 ID는 비어 있을 수 없고 100자를 넘을 수 없습니다."),
    INVALID_RESERVATION_EXPIRY(
        ErrorType.VALIDATION,
        "INV_INVALID_RESERVATION_EXPIRY",
        "만료 시각은 현재 이후이고 채널의 최대 만료 시각을 넘을 수 없습니다.",
    ),
    INVALID_RESERVATION_ITEMS(
        ErrorType.VALIDATION,
        "INV_INVALID_RESERVATION_ITEMS",
        "예약 상품은 1개 이상 100개 이하이고 중복될 수 없으며 수량은 1 이상이고 할당 수량의 합과 같아야 합니다.",
    ),
    INVALID_RESERVATION_STATE(
        ErrorType.CONFLICT,
        "INV_INVALID_RESERVATION_STATE",
        "예약의 현재 상태에서는 요청한 작업을 할 수 없습니다.",
    ),
    RESERVATION_EXPIRED(ErrorType.CONFLICT, "INV_RESERVATION_EXPIRED", "만료 시각이 지난 예약입니다."),
    INVALID_FULFILLMENT(
        ErrorType.VALIDATION,
        "INV_INVALID_FULFILLMENT",
        "출고 수량은 예약의 모든 할당 재고 행에 대해 한 번씩, 0 이상 할당 수량 이하로 보내야 합니다.",
    ),
    RESERVATION_NOT_FOUND(ErrorType.NOT_FOUND, "INV_RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다."),
    RESERVATION_CHANGED_CONCURRENTLY(
        ErrorType.CONFLICT,
        "INV_RESERVATION_CHANGED_CONCURRENTLY",
        "다른 요청이 같은 예약을 동시에 변경했습니다. 다시 시도해 주세요.",
    ),
    DUPLICATE_ORDER_RESERVATION(
        ErrorType.CONFLICT,
        "INV_DUPLICATE_ORDER_RESERVATION",
        "같은 채널의 같은 주문에 이미 살아 있는 예약이 있습니다.",
    ),
    PRODUCT_NOT_RESERVABLE(ErrorType.CONFLICT, "INV_PRODUCT_NOT_RESERVABLE", "비활성 상품은 예약할 수 없습니다."),
    DUPLICATE_RESERVATION_KEY(ErrorType.CONFLICT, "INV_DUPLICATE_RESERVATION_KEY", "이미 처리한 예약 멱등 키입니다."),
}
