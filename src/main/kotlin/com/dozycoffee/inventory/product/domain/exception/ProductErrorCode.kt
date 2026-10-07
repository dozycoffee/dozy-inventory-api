package com.dozycoffee.inventory.product.domain.exception

import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType

enum class ProductErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    INVALID_PRODUCT_CODE(ErrorType.VALIDATION, "INV_INVALID_PRODUCT_CODE", "상품 코드는 비어 있을 수 없습니다."),
    INVALID_PRODUCT_NAME(ErrorType.VALIDATION, "INV_INVALID_PRODUCT_NAME", "상품명은 비어 있을 수 없습니다."),
    INVALID_CATEGORY(ErrorType.VALIDATION, "INV_INVALID_PRODUCT_CATEGORY", "상품 분류는 필수입니다."),
    INVALID_UNIT(ErrorType.VALIDATION, "INV_INVALID_PRODUCT_UNIT", "단위는 비어 있을 수 없습니다."),
    INVALID_SHELF_LIFE_DAYS(ErrorType.VALIDATION, "INV_INVALID_SHELF_LIFE_DAYS", "유통기한 일수는 0 이상이어야 합니다."),
    INVALID_PAGE_REQUEST(ErrorType.VALIDATION, "INV_INVALID_PAGE_REQUEST", "페이지는 0 이상, 크기는 1 이상 100 이하여야 합니다."),
    PRODUCT_NOT_FOUND(ErrorType.NOT_FOUND, "INV_PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."),
    DUPLICATE_PRODUCT_CODE(ErrorType.CONFLICT, "INV_DUPLICATE_PRODUCT_CODE", "이미 존재하는 상품 코드입니다."),
}
