package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.GetReconciliationProposalUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.SearchReconciliationProposalsUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.SearchReconciliationProposalsQuery
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.abs

/** 대사 보정안 조회(ADR-0033). 읽기 전용이라 락을 걸지 않고, 요청자의 창고 범위를 적용한다(ADR-0032) */
@Service
class ReconciliationProposalQueryService(
    private val stockAdjustmentRepository: StockAdjustmentRepository,
    private val getLotUseCase: GetLotUseCase,
    private val getProductUseCase: GetProductUseCase,
    private val adjustmentPolicy: AdjustmentPolicy,
) : GetReconciliationProposalUseCase,
    SearchReconciliationProposalsUseCase {
    @Transactional(readOnly = true)
    override suspend fun getById(
        stockAdjustmentId: Long,
        scope: WarehouseScope,
    ): ReconciliationProposalResult {
        val adjustment: StockAdjustment = stockAdjustmentRepository.findById(stockAdjustmentId) ?: throw AdjustmentNotFoundException()
        if (adjustment.adjustmentType != AdjustmentType.RECONCILIATION) throw AdjustmentNotFoundException()
        scope.require(adjustment.warehouseId)
        return resultsOf(listOf(adjustment)).single()
    }

    @Transactional(readOnly = true)
    override suspend fun search(query: SearchReconciliationProposalsQuery): PageResult<ReconciliationProposalResult> {
        val warehouseIds: Set<Long>? = query.scope.narrow(query.warehouseId?.let(::setOf))
        val proposals: List<StockAdjustment> =
            stockAdjustmentRepository.searchReconciliations(warehouseIds, query.status, query.page.toLong() * query.size, query.size)
        return PageResult(
            resultsOf(proposals),
            query.page,
            query.size,
            stockAdjustmentRepository.countReconciliations(warehouseIds, query.status),
        )
    }

    /** 보정안마다 Lot(상품, Lot 번호)을 붙이고 임계치를 넘는지 계산한다. Lot과 상품 조회는 한 번씩 모아서 한다 */
    private suspend fun resultsOf(proposals: List<StockAdjustment>): List<ReconciliationProposalResult> {
        if (proposals.isEmpty()) return emptyList()
        val lots: Map<Long, LotResult> =
            getLotUseCase.getByIds(proposals.map { it.items.single().lotId }.toSet()).associateBy(LotResult::lotId)
        val thresholds: Map<Long, Int> =
            lots.values
                .map(LotResult::productId)
                .toSet()
                .associateWith { adjustmentPolicy.approvalThresholdFor(getProductUseCase.getById(it).category.name) }
        return proposals.map { proposal: StockAdjustment ->
            val lot: LotResult = checkNotNull(lots[proposal.items.single().lotId]) { "보정안 항목의 Lot을 찾을 수 없다" }
            ReconciliationProposalResult.from(
                proposal,
                lot,
                abs(
                    proposal.items
                        .single()
                        .quantityChange
                        .toLong(),
                ) > thresholds.getValue(lot.productId),
            )
        }
    }
}
