package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Lot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LotService(
    private val lotRepository: LotRepository,
) : GetLotUseCase {
    @Transactional(readOnly = true)
    override suspend fun getByIds(lotIds: Set<Long>): List<LotResult> = lotRepository.findAllByIds(lotIds).map(::toResult)

    @Transactional(readOnly = true)
    override suspend fun findByProductAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): LotResult? = lotRepository.findByProductIdAndLotNumber(productId, lotNumber)?.let(::toResult)

    @Transactional(readOnly = true)
    override suspend fun getByProductAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): LotResult {
        val lot: Lot = lotRepository.findByProductIdAndLotNumber(productId, lotNumber) ?: throw LotNotFoundException()
        return toResult(lot)
    }
}

private fun toResult(lot: Lot): LotResult =
    LotResult(checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" }, lot.productId, lot.lotNumber, lot.manufactureDate, lot.expirationDate)
