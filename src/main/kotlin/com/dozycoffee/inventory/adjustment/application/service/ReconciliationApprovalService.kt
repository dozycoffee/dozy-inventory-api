package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.ApproveReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.RejectReconciliationUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.ReviewReconciliationCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationProposalResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentStatus
import com.dozycoffee.inventory.adjustment.domain.enumeration.AdjustmentType
import com.dozycoffee.inventory.adjustment.domain.enumeration.ReconciliationRevision
import com.dozycoffee.inventory.adjustment.domain.exception.AdjustmentNotFoundException
import com.dozycoffee.inventory.adjustment.domain.exception.InvalidAdjustmentStateException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationAdminRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.ReconciliationChangedException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetWarehouseQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustmentKind
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.WarehouseQuantityResult
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 대사 보정안의 승인·거부(ADR-0033). 승인은 WMS 수량과 지금의 inventory 수량을 다시 확인한 뒤(트랜잭션 밖) 숫자가 그대로일 때만
 * 한 트랜잭션에서 `PENDING` → `APPLIED` 조건부 UPDATE와 재고 반영(`RECONCILIATION` 이력)을 한다. 숫자가 바뀌었으면 보정안만 새 값으로 고치고
 * 반영하지 않은 채 [ReconciliationChangedException]으로 알린다(고친 값은 커밋된다). 반영이 실패하면(예: 감소가 가용 수량을 넘음) 상태도 함께
 * 롤백되어 보정안은 `PENDING`으로 남는다. 같은 보정안을 동시에 승인하면 조건부 UPDATE에서 밀린 쪽이 다시 읽어 판정하므로 이미 반영된 보정안은
 * 현재 상태를 돌려준다. 이력의 멱등 키는 보정안의 멱등 키라 재고에 두 번 반영될 수 없다.
 */
@Service
class ReconciliationApprovalService(
    private val stockAdjustmentRepository: StockAdjustmentRepository,
    private val getLotUseCase: GetLotUseCase,
    private val getProductUseCase: GetProductUseCase,
    private val getWarehouseQuantitiesUseCase: GetWarehouseQuantitiesUseCase,
    private val wmsStockPort: WmsStockPort,
    private val adjustmentPolicy: AdjustmentPolicy,
    private val adjustInventoryUseCase: AdjustInventoryUseCase,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : ApproveReconciliationUseCase,
    RejectReconciliationUseCase {
    override suspend fun approve(command: ReviewReconciliationCommand): ReconciliationProposalResult {
        val reviewer: RequesterService = RequesterService.of(command.reviewer)
        repeat(MAX_ATTEMPTS) {
            val adjustment: StockAdjustment = load(command)
            val lot: LotResult = lotOf(adjustment)
            val requiresAdmin: Boolean = requiresAdmin(adjustment, lot)
            if (adjustment.status == AdjustmentStatus.APPLIED) return ReconciliationProposalResult.from(adjustment, lot, requiresAdmin)
            if (adjustment.status != AdjustmentStatus.PENDING) throw InvalidAdjustmentStateException()
            if (requiresAdmin && !command.isAdmin) throw ReconciliationAdminRequiredException()

            confirmQuantities(adjustment, lot)
            adjustment.approve(reviewer.value, now())
            val inventoryId: Long? = transactionalOperator.executeAndAwait { apply(adjustment, lot, reviewer) }
            // 조건부 UPDATE에서 밀렸으면 다른 요청이 먼저 처리한 것이라 다시 읽어 판정한다
            if (inventoryId != null) return ReconciliationProposalResult.from(adjustment, lot, requiresAdmin, inventoryId)
        }
        throw InvalidAdjustmentStateException()
    }

    override suspend fun reject(command: ReviewReconciliationCommand): ReconciliationProposalResult {
        repeat(MAX_ATTEMPTS) {
            val adjustment: StockAdjustment = load(command)
            val lot: LotResult = lotOf(adjustment)
            val requiresAdmin: Boolean = requiresAdmin(adjustment, lot)
            if (adjustment.status == AdjustmentStatus.REJECTED) return ReconciliationProposalResult.from(adjustment, lot, requiresAdmin)
            if (adjustment.status != AdjustmentStatus.PENDING) throw InvalidAdjustmentStateException()

            adjustment.reject()
            val rejected: Boolean =
                checkNotNull(
                    transactionalOperator.executeAndAwait { stockAdjustmentRepository.updateState(adjustment, AdjustmentStatus.PENDING) },
                ) {
                    "거부 결과가 없다"
                }
            if (rejected) return ReconciliationProposalResult.from(adjustment, lot, requiresAdmin)
        }
        throw InvalidAdjustmentStateException()
    }

    /** 보정안을 읽고 대사 보정안이 맞는지, 창고가 요청자의 범위 안인지 확인한다. 실사 조정은 없는 것으로 본다 */
    private suspend fun load(command: ReviewReconciliationCommand): StockAdjustment {
        val adjustment: StockAdjustment =
            stockAdjustmentRepository.findById(command.stockAdjustmentId) ?: throw AdjustmentNotFoundException()
        if (adjustment.adjustmentType != AdjustmentType.RECONCILIATION) throw AdjustmentNotFoundException()
        command.scope.require(adjustment.warehouseId)
        return adjustment
    }

    private suspend fun lotOf(adjustment: StockAdjustment): LotResult =
        getLotUseCase.getByIds(setOf(adjustment.items.single().lotId)).single()

    /** 항목의 변동량 절댓값이 상품 카테고리의 임계치를 넘으면 본사 관리자만 승인할 수 있다 */
    private suspend fun requiresAdmin(
        adjustment: StockAdjustment,
        lot: LotResult,
    ): Boolean {
        val threshold: Int = adjustmentPolicy.approvalThresholdFor(getProductUseCase.getById(lot.productId).category.name)
        return abs(
            adjustment.items
                .single()
                .quantityChange
                .toLong(),
        ) > threshold
    }

    /**
     * WMS 수량과 지금의 inventory 수량을 다시 확인한다. 보정안의 값과 같으면 그대로 진행하고, 다르면 보정안을 새 값으로 고치거나
     * (불일치가 사라졌으면) 거부 상태로 닫아 저장한 뒤 [ReconciliationChangedException]을 던진다.
     */
    private suspend fun confirmQuantities(
        adjustment: StockAdjustment,
        lot: LotResult,
    ) {
        val item: StockAdjustmentItem = adjustment.items.single()
        val wmsNow: Int =
            wmsStockPort
                .findQuantities(adjustment.warehouseId, lot.productId, lot.lotNumber)
                .filter { it.qualityStatus == item.qualityStatus }
                .sumOf { it.quantity }
        val inventoryNow: Int =
            getWarehouseQuantitiesUseCase
                .getQuantities(adjustment.warehouseId, lot.productId, lot.lotNumber)
                .firstOrNull { it.qualityStatus == item.qualityStatus }
                ?.quantity ?: 0
        when (adjustment.revise(inventoryNow, wmsNow)) {
            ReconciliationRevision.UNCHANGED -> {
                return
            }

            ReconciliationRevision.UPDATED -> {
                transactionalOperator.executeAndAwait { stockAdjustmentRepository.updateReconciliationItem(item) }
            }

            ReconciliationRevision.RESOLVED -> {
                transactionalOperator.executeAndAwait { stockAdjustmentRepository.updateState(adjustment, AdjustmentStatus.PENDING) }
            }
        }
        throw ReconciliationChangedException()
    }

    /** 처리 대기 → 반영 상태를 조건부로 바꾸고 재고에 반영한다. 다른 요청에 밀렸으면 아무것도 바꾸지 않고 null을 반환한다 */
    private suspend fun apply(
        adjustment: StockAdjustment,
        lot: LotResult,
        reviewer: RequesterService,
    ): Long? {
        if (!stockAdjustmentRepository.updateState(adjustment, AdjustmentStatus.PENDING)) return null
        val item: StockAdjustmentItem = adjustment.items.single()
        val itemId: Long = checkNotNull(item.stockAdjustmentItemId) { "저장된 조정 항목은 식별자가 있어야 한다" }
        val adjusted: AdjustInventoryResult.Item =
            adjustInventoryUseCase
                .adjust(
                    AdjustInventoryCommand(
                        warehouseId = adjustment.warehouseId,
                        items =
                            listOf(
                                AdjustInventoryCommand.Item(lot.productId, item.lotId, item.qualityStatus, item.quantityChange, itemId),
                            ),
                        idempotencyKey = adjustment.idempotencyKey.value,
                        requesterService = reviewer.value,
                        kind = AdjustmentKind.RECONCILIATION,
                    ),
                ).items
                .single()
        stockAdjustmentRepository.assignInventoryIds(mapOf(itemId to adjusted.inventoryId))
        return adjusted.inventoryId
    }

    /** 시각 컬럼이 마이크로초(DATETIME(6))라 먼저 잘라 둔다 */
    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
}

private const val MAX_ATTEMPTS: Int = 3
