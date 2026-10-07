package com.dozycoffee.ims.inventory.adapter.out.persistence

import com.dozycoffee.ims.global.persistence.translatingDuplicateKey
import com.dozycoffee.ims.inventory.application.port.out.LotRepository
import com.dozycoffee.ims.inventory.domain.exception.DuplicateLotException
import com.dozycoffee.ims.inventory.domain.model.Lot
import org.springframework.stereotype.Component

@Component
class LotPersistenceAdapter(
    private val lotR2dbcRepository: LotR2dbcRepository,
) : LotRepository {
    override suspend fun save(lot: Lot): Lot {
        val entity: LotEntity = LotEntity.from(lot)
        lot.lotId?.let { lotId: Long ->
            val existing: LotEntity = checkNotNull(lotR2dbcRepository.findById(lotId)) { "갱신할 Lot이 없다: $lotId" }
            entity.copyAuditFieldsFrom(existing)
        }
        return translatingDuplicateKey(duplicate = { DuplicateLotException() }) {
            lotR2dbcRepository.save(entity).toDomain()
        }
    }

    override suspend fun findById(lotId: Long): Lot? = lotR2dbcRepository.findById(lotId)?.toDomain()

    override suspend fun findByProductIdAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): Lot? = lotR2dbcRepository.findByProductIdAndLotNumber(productId, lotNumber)?.toDomain()
}
