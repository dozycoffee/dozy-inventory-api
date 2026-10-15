package com.dozycoffee.inventory.product.application.port.`in`

import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult

interface GetProductsUseCase {
    /** 상품 ID 여러 개를 한 번에 조회해 ID별로 돌려준다. 없는 ID는 결과에 없다. 다른 도메인이 목록의 상품 정보를 채울 때 쓴다 */
    suspend fun getByIds(productIds: Set<Long>): Map<Long, ProductResult>
}
