package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.GetOutboundResultUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ShipInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 출고 확정의 재고 반영(ADR-0024). 출고한 행은 총 수량과 예약 수량을 함께 줄이고 `OUTBOUND` 이력을 남기며, 결품은 예약 수량만 되돌린다.
 * 호출한 서비스의 트랜잭션 안에서 실행하며 트랜잭션을 직접 열지 않는다. 재고 행은 `inventory_id` 오름차순으로 갱신해 데드락을 피한다.
 */
@Service
class OutboundService(
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
) : ShipInventoryUseCase,
    GetOutboundResultUseCase {
    override suspend fun ship(command: ShipInventoryCommand): ShipResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val requester: RequesterService = RequesterService.of(command.requesterService)
        val items: List<ShipResult.Item> =
            command.items.sortedBy(ShipInventoryCommand.Item::inventoryId).map { item: ShipInventoryCommand.Item ->
                val quantityAfter: Int? =
                    if (item.shippedQuantity > 0) shipOne(item, key, requester, command.referenceId) else null
                if (item.releasedQuantity > 0) inventoryRepository.release(item.inventoryId, item.releasedQuantity)
                ShipResult.Item(item.inventoryId, quantityAfter)
            }
        return ShipResult(items)
    }

    @Transactional(readOnly = true)
    override suspend fun getQuantitiesAfter(
        idempotencyKey: String,
        referenceId: Long,
    ): Map<Long, Int> =
        inventoryHistoryRepository
            .findAllByIdempotencyKey(IdempotencyKey.of(idempotencyKey))
            .filter {
                it.historyType == HistoryType.OUTBOUND && it.referenceType == ReferenceType.RESERVATION &&
                    it.referenceId == referenceId
            }.associate { it.inventoryId to it.quantityAfter }

    private suspend fun shipOne(
        item: ShipInventoryCommand.Item,
        key: IdempotencyKey,
        requester: RequesterService,
        referenceId: Long,
    ): Int {
        val inventory: Inventory = inventoryRepository.ship(item.inventoryId, item.shippedQuantity)
        inventoryHistoryRepository.save(
            InventoryHistory.create(
                inventoryId = item.inventoryId,
                historyType = HistoryType.OUTBOUND,
                quantityChange = -item.shippedQuantity,
                quantityAfter = inventory.quantity,
                referenceType = ReferenceType.RESERVATION,
                referenceId = referenceId,
                idempotencyKey = key,
                requesterService = requester,
            ),
        )
        inventoryEventPublisher.publish(
            InventoryEvent(
                eventType = InventoryEventType.DECREASED,
                inventoryId = item.inventoryId,
                warehouseId = inventory.warehouseId,
                productId = inventory.productId,
                lotId = inventory.lotId,
                qualityStatus = inventory.qualityStatus,
                quantityChange = -item.shippedQuantity,
                quantityAfter = inventory.quantity,
                referenceType = ReferenceType.RESERVATION,
                referenceId = referenceId,
                idempotencyKey = key.value,
            ),
        )
        return inventory.quantity
    }
}
