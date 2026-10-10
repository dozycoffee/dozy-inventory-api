package com.dozycoffee.inventory.adjustment.application.service

import com.dozycoffee.inventory.adjustment.application.port.`in`.RequestStockAdjustmentUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.command.RequestStockAdjustmentCommand
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.StockAdjustmentResult
import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import com.dozycoffee.inventory.adjustment.application.port.out.StockAdjustmentRepository
import com.dozycoffee.inventory.adjustment.domain.exception.ApprovalRequiredException
import com.dozycoffee.inventory.adjustment.domain.exception.DuplicateAdjustmentKeyException
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustment
import com.dozycoffee.inventory.adjustment.domain.model.StockAdjustmentItem
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAdjustedQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetLotUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 실사 조정 요청(ADR-0026). 조정과 항목 저장, 재고 반영, 항목의 대상 재고 행 기록을 한 트랜잭션으로 처리한다.
 * 항목 ID가 이력의 원인 문서 ID라서 조정을 먼저 저장한 뒤 그 ID로 재고에 반영한다.
 * 같은 멱등 키가 동시에 들어오면 늦은 쪽의 저장이 중복 키로 실패해 롤백되므로(재고 반영도 되돌아간다) 트랜잭션 밖에서
 * 저장된 조정으로 이전 결과를 반환한다. Lot 조회와 승인 확인은 읽기만 하므로 트랜잭션 밖에서 한다.
 */
@Service
class StockAdjustmentService(
    private val getLotUseCase: GetLotUseCase,
    private val getProductUseCase: GetProductUseCase,
    private val adjustInventoryUseCase: AdjustInventoryUseCase,
    private val getAdjustedQuantitiesUseCase: GetAdjustedQuantitiesUseCase,
    private val stockAdjustmentRepository: StockAdjustmentRepository,
    private val adjustmentPolicy: AdjustmentPolicy,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : RequestStockAdjustmentUseCase {
    override suspend fun request(command: RequestStockAdjustmentCommand): StockAdjustmentResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val requester: RequesterService = RequesterService.of(command.requesterService)
        val lotIds: List<Long> =
            command.items.map { getLotUseCase.getByProductAndLotNumber(it.productId, it.lotNumber).lotId }

        findPreviousResult(command, key, lotIds)?.let { return it }
        requireApprovalIfNeeded(command)
        return try {
            checkNotNull(transactionalOperator.executeAndAwait { apply(command, key, requester, lotIds) }) { "조정 결과가 없다" }
        } catch (e: DuplicateAdjustmentKeyException) {
            findPreviousResult(command, key, lotIds) ?: throw e
        }
    }

    /** 변동량이 상품 카테고리의 임계치를 넘는 항목이 하나라도 있는데 승인자가 없으면 거부한다 */
    private suspend fun requireApprovalIfNeeded(command: RequestStockAdjustmentCommand) {
        if (!command.approvedBy.isNullOrBlank()) return
        val thresholds: Map<Long, Int> =
            command.items
                .map { it.productId }
                .distinct()
                .associateWith { adjustmentPolicy.approvalThresholdFor(getProductUseCase.getById(it).category.name) }
        if (command.items.any { abs(it.quantityChange.toLong()) > thresholds.getValue(it.productId) }) throw ApprovalRequiredException()
    }

    private suspend fun apply(
        command: RequestStockAdjustmentCommand,
        key: IdempotencyKey,
        requester: RequesterService,
        lotIds: List<Long>,
    ): StockAdjustmentResult {
        val adjustment: StockAdjustment =
            StockAdjustment.audit(
                warehouseId = command.warehouseId,
                externalReferenceId = command.externalReferenceId,
                items =
                    command.items.indices.map {
                        StockAdjustmentItem.create(lotIds[it], command.items[it].qualityStatus, command.items[it].quantityChange)
                    },
                requestedBy = requester,
                approvedBy = command.approvedBy?.takeIf { it.isNotBlank() },
                idempotencyKey = key,
                now = LocalDateTime.now(clock),
            )
        val saved: StockAdjustment = stockAdjustmentRepository.save(adjustment)

        val productIds: Map<Pair<Long, QualityStatus>, Long> =
            command.items.indices.associate { (lotIds[it] to command.items[it].qualityStatus) to command.items[it].productId }
        val adjusted: AdjustInventoryResult =
            adjustInventoryUseCase.adjust(
                AdjustInventoryCommand(
                    warehouseId = command.warehouseId,
                    items =
                        saved.items.map { item: StockAdjustmentItem ->
                            AdjustInventoryCommand.Item(
                                productId = productIds.getValue(item.lotId to item.qualityStatus),
                                lotId = item.lotId,
                                qualityStatus = item.qualityStatus,
                                quantityChange = item.quantityChange,
                                referenceId = checkNotNull(item.stockAdjustmentItemId) { "저장된 조정 항목은 식별자가 있어야 한다" },
                            )
                        },
                    idempotencyKey = key.value,
                    requesterService = requester.value,
                ),
            )
        stockAdjustmentRepository.assignInventoryIds(adjusted.items.associate { it.referenceId to it.inventoryId })
        return resultOf(
            saved,
            adjusted.items.associate { it.referenceId to it.inventoryId },
            adjusted.items.associate {
                it.referenceId to
                    it.quantityAfter
            },
        )
    }

    /** 같은 멱등 키로 이미 처리했으면 저장된 조정과 이력에서 이전 결과를 다시 만든다. 내용이 다르면 거부한다 */
    private suspend fun findPreviousResult(
        command: RequestStockAdjustmentCommand,
        key: IdempotencyKey,
        lotIds: List<Long>,
    ): StockAdjustmentResult? {
        val stored: StockAdjustment = stockAdjustmentRepository.findByIdempotencyKey(key) ?: return null
        val requested: Set<Triple<Long, QualityStatus, Int>> =
            command.items.indices
                .map { Triple(lotIds[it], command.items[it].qualityStatus, command.items[it].quantityChange) }
                .toSet()
        val storedItems: Set<Triple<Long, QualityStatus, Int>> =
            stored.items
                .map {
                    Triple(
                        it.lotId,
                        it.qualityStatus,
                        it.quantityChange,
                    )
                }.toSet()
        if (stored.warehouseId != command.warehouseId || stored.externalReferenceId != command.externalReferenceId ||
            requested != storedItems
        ) {
            throw IdempotencyKeyConflictException()
        }
        val after: Map<Long, Int> = getAdjustedQuantitiesUseCase.getQuantitiesAfter(key.value)
        return resultOf(
            stored,
            stored.items.associate {
                checkNotNull(it.stockAdjustmentItemId) to
                    checkNotNull(it.inventoryId) { "반영된 조정 항목은 재고 행이 있어야 한다" }
            },
            after,
        )
    }

    private fun resultOf(
        adjustment: StockAdjustment,
        inventoryIds: Map<Long, Long>,
        quantitiesAfter: Map<Long, Int>,
    ): StockAdjustmentResult =
        StockAdjustmentResult(
            stockAdjustmentId = checkNotNull(adjustment.stockAdjustmentId) { "저장된 조정은 식별자가 있어야 한다" },
            warehouseId = adjustment.warehouseId,
            externalReferenceId = checkNotNull(adjustment.externalReferenceId) { "실사 조정은 실사 건 ID가 있어야 한다" },
            status = adjustment.status,
            approvedBy = adjustment.approvedBy,
            items =
                adjustment.items.map { item: StockAdjustmentItem ->
                    val itemId: Long = checkNotNull(item.stockAdjustmentItemId) { "저장된 조정 항목은 식별자가 있어야 한다" }
                    StockAdjustmentResult.Item(
                        stockAdjustmentItemId = itemId,
                        inventoryId = inventoryIds.getValue(itemId),
                        lotId = item.lotId,
                        qualityStatus = item.qualityStatus,
                        quantityChange = item.quantityChange,
                        quantityAfter = quantitiesAfter.getValue(itemId),
                    )
                },
        )
}
