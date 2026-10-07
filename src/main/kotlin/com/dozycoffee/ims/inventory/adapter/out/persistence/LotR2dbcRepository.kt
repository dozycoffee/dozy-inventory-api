package com.dozycoffee.ims.inventory.adapter.out.persistence

import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface LotR2dbcRepository : CoroutineCrudRepository<LotEntity, Long> {
    suspend fun findByProductIdAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): LotEntity?
}
