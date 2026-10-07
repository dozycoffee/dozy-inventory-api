package com.dozycoffee.ims.inventory.application.port.out

import com.dozycoffee.ims.inventory.domain.model.Lot

interface LotRepository {
    /** 식별자가 없으면 저장하고 있으면 갱신한다. 같은 상품에 같은 Lot 번호가 있으면 [com.dozycoffee.ims.inventory.domain.exception.DuplicateLotException] */
    suspend fun save(lot: Lot): Lot

    suspend fun findById(lotId: Long): Lot?

    /** Lot 번호는 대소문자를 구분해 비교한다 */
    suspend fun findByProductIdAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): Lot?
}
