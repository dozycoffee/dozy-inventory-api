package com.dozycoffee.inventory.inventory.application.port.`in`

import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult

interface GetLotUseCase {
    /** 상품과 Lot 번호로 등록된 Lot을 찾는다. 없으면 null이다(대사가 WMS에만 있는 Lot을 건너뛰려고 쓴다) */
    suspend fun findByProductAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): LotResult?

    /** 상품과 Lot 번호(대소문자 구분)로 등록된 Lot을 찾는다. 없으면 [com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException] */
    suspend fun getByProductAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): LotResult
}
