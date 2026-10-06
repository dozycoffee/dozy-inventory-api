package com.dozycoffee.ims.product.application.port.out

import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult

/** 상품 마스터 변경 이벤트. 구독자는 [product] 스냅샷으로 사본을 갱신한다 */
data class ProductEvent(
    val eventType: ProductEventType,
    val product: ProductResult,
)

enum class ProductEventType {
    REGISTERED,
    STATUS_CHANGED,
}
