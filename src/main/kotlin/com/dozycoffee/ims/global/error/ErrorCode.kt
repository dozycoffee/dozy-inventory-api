package com.dozycoffee.ims.global.error

/**
 * 응답의 `code`로 쓰이는 값이다. 범용 코드는 dozy-auth 에러 코드 표의 이름을 그대로 쓰고,
 * 도메인 코드는 다른 서비스와 겹치지 않도록 `IMS_` 접두사를 붙인다(예: `IMS_PRODUCT_NOT_FOUND`).
 */
interface ErrorCode {
    val code: String
    val message: String
    val errorType: ErrorType
}
