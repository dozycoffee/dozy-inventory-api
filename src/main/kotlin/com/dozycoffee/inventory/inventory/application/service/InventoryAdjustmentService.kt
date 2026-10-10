package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.AdjustInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetAdjustedQuantitiesUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AdjustInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AdjustInventoryResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 실사 조정의 재고 반영(ADR-0026). 증가는 행이 없으면 만들고 감소는 가용 수량 안에서만 줄이며, `ADJUSTMENT` 이력과 증가·감소 이벤트를
 * 남기고 반영한 행의 할당 보류를 푼다. 호출한 서비스의 트랜잭션 안에서 실행하며 트랜잭션을 직접 열지 않는다.
 * 이미 있는 재고 행은 `inventory_id` 오름차순으로, 새로 만들 행은 그 뒤에 (Lot, 품질 상태) 순서로 갱신해 데드락을 피한다.
 */
@Service
class InventoryAdjustmentService(
    private val lotRepository: LotRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
) : AdjustInventoryUseCase,
    GetAdjustedQuantitiesUseCase {
    override suspend fun adjust(command: AdjustInventoryCommand): AdjustInventoryResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val requester: RequesterService = RequesterService.of(command.requesterService)
        val targets: List<Pair<AdjustInventoryCommand.Item, Inventory?>> = command.items.map { it to findCurrent(command.warehouseId, it) }
        val ordered: List<Pair<AdjustInventoryCommand.Item, Inventory?>> =
            targets.filter { it.second != null }.sortedBy { it.second?.inventoryId } +
                targets.filter { it.second == null }.sortedWith(compareBy({ it.first.lotId }, { it.first.qualityStatus }))
        val applied: Map<Long, AdjustInventoryResult.Item> =
            ordered.associate { (item, current) -> item.referenceId to applyOne(command.warehouseId, item, current, key, requester) }
        return AdjustInventoryResult(command.items.map { applied.getValue(it.referenceId) })
    }

    @Transactional(readOnly = true)
    override suspend fun getQuantitiesAfter(idempotencyKey: String): Map<Long, Int> =
        inventoryHistoryRepository
            .findAllByIdempotencyKey(IdempotencyKey.of(idempotencyKey))
            .filter { it.historyType == HistoryType.ADJUSTMENT && it.referenceType == ReferenceType.STOCK_ADJUSTMENT_ITEM }
            .associate { it.referenceId to it.quantityAfter }

    /** 항목의 Lot이 그 상품의 Lot인지 확인하고 지금의 재고 행을 찾는다. 읽기만 하고 락을 잡지 않는다 */
    private suspend fun findCurrent(
        warehouseId: Long,
        item: AdjustInventoryCommand.Item,
    ): Inventory? {
        val lot: Lot? = lotRepository.findById(item.lotId)
        if (lot == null || lot.productId != item.productId) throw LotNotFoundException()
        return inventoryRepository.findByKey(InventoryKey(warehouseId, item.lotId, item.qualityStatus))
    }

    private suspend fun applyOne(
        warehouseId: Long,
        item: AdjustInventoryCommand.Item,
        current: Inventory?,
        key: IdempotencyKey,
        requester: RequesterService,
    ): AdjustInventoryResult.Item {
        val changed: Inventory =
            if (item.quantityChange > 0) {
                val rowKey = InventoryKey(warehouseId, item.lotId, item.qualityStatus)
                inventoryRepository.increase(rowKey, item.productId, item.quantityChange)
            } else {
                val row: Inventory = current ?: throw InventoryNotFoundException()
                inventoryRepository.decrease(checkNotNull(row.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }, -item.quantityChange)
            }
        val inventoryId: Long = checkNotNull(changed.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }
        if (changed.allocationHold) inventoryRepository.releaseAllocationHold(inventoryId)
        inventoryHistoryRepository.save(
            InventoryHistory.create(
                inventoryId = inventoryId,
                historyType = HistoryType.ADJUSTMENT,
                quantityChange = item.quantityChange,
                quantityAfter = changed.quantity,
                referenceType = ReferenceType.STOCK_ADJUSTMENT_ITEM,
                referenceId = item.referenceId,
                idempotencyKey = key,
                requesterService = requester,
            ),
        )
        inventoryEventPublisher.publish(
            InventoryEvent(
                eventType = if (item.quantityChange > 0) InventoryEventType.INCREASED else InventoryEventType.DECREASED,
                inventoryId = inventoryId,
                warehouseId = warehouseId,
                productId = item.productId,
                lotId = item.lotId,
                qualityStatus = item.qualityStatus,
                quantityChange = item.quantityChange,
                quantityAfter = changed.quantity,
                referenceType = ReferenceType.STOCK_ADJUSTMENT_ITEM,
                referenceId = item.referenceId,
                idempotencyKey = key.value,
            ),
        )
        return AdjustInventoryResult.Item(item.referenceId, inventoryId, changed.quantity)
    }
}
