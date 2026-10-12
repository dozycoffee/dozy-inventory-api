package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmReturnUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ReturnResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 반품 복귀 반영(ADR-0029). 수량·이력·이벤트는 한 트랜잭션으로 저장한다. 입고 확정과 달리 Lot을 만들지 않고 이미 등록된 Lot만 받는다.
 * 같은 멱등 키가 동시에 들어오면 늦은 쪽의 이력 저장이 중복 키로 실패해 롤백되므로(수량 증가도 되돌아간다)
 * 트랜잭션 밖에서 저장된 이력으로 이전 결과를 반환한다.
 */
@Service
class ReturnService(
    private val getProductUseCase: GetProductUseCase,
    private val lotRepository: LotRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
    private val transactionalOperator: TransactionalOperator,
) : ConfirmReturnUseCase {
    override suspend fun confirm(command: ConfirmReturnCommand): ReturnResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        validate(command)

        findPreviousResult(command, key)?.let { return it }
        return try {
            checkNotNull(transactionalOperator.executeAndAwait { receive(command, key) }) { "반품 반영 결과가 없다" }
        } catch (e: DuplicateIdempotencyKeyException) {
            findPreviousResult(command, key) ?: throw e
        }
    }

    private fun validate(command: ConfirmReturnCommand) {
        Inventory.requireValidAmount(command.quantity)
        RequesterService.of(command.requesterService)
        if (!command.qualityStatus.isInspectionResult) throw InvalidDomainValueException(InventoryErrorCode.INVALID_RETURN_QUALITY_STATUS)
    }

    private suspend fun receive(
        command: ConfirmReturnCommand,
        key: IdempotencyKey,
    ): ReturnResult {
        getProductUseCase.getById(command.productId)
        val lot: Lot = lotRepository.findByProductIdAndLotNumber(command.productId, command.lotNumber) ?: throw LotNotFoundException()
        val lotId: Long = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" }

        val inventory: Inventory =
            inventoryRepository.increase(
                InventoryKey(command.warehouseId, lotId, command.qualityStatus),
                command.productId,
                command.quantity,
            )
        val inventoryId: Long = checkNotNull(inventory.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }
        val history: InventoryHistory =
            inventoryHistoryRepository.save(
                InventoryHistory.create(
                    inventoryId = inventoryId,
                    historyType = HistoryType.RETURN,
                    quantityChange = command.quantity,
                    quantityAfter = inventory.quantity,
                    referenceType = ReferenceType.RETURN_ITEM,
                    referenceId = command.referenceId,
                    idempotencyKey = key,
                    requesterService = RequesterService.of(command.requesterService),
                ),
            )
        inventoryEventPublisher.publish(
            InventoryEvent(
                eventType = InventoryEventType.INCREASED,
                inventoryId = inventoryId,
                warehouseId = command.warehouseId,
                productId = command.productId,
                lotId = lotId,
                qualityStatus = command.qualityStatus,
                quantityChange = command.quantity,
                quantityAfter = inventory.quantity,
                referenceType = ReferenceType.RETURN_ITEM,
                referenceId = command.referenceId,
                idempotencyKey = key.value,
            ),
        )
        return result(inventory, lotId, history)
    }

    /** 같은 멱등 키로 이미 처리했으면 저장된 이력에서 이전 결과를 다시 만든다. 내용이 다르면 거부한다 */
    private suspend fun findPreviousResult(
        command: ConfirmReturnCommand,
        key: IdempotencyKey,
    ): ReturnResult? {
        val histories: List<InventoryHistory> = inventoryHistoryRepository.findAllByIdempotencyKey(key)
        if (histories.isEmpty()) return null
        val history: InventoryHistory = histories.singleOrNull() ?: throw IdempotencyKeyConflictException()

        val inventory: Inventory = checkNotNull(inventoryRepository.findById(history.inventoryId)) { "이력의 재고 행이 없다" }
        val lot: Lot = checkNotNull(lotRepository.findById(inventory.lotId)) { "재고 행의 Lot이 없다" }
        val sameRequest: Boolean =
            history.historyType == HistoryType.RETURN &&
                history.referenceType == ReferenceType.RETURN_ITEM &&
                history.referenceId == command.referenceId &&
                history.quantityChange == command.quantity &&
                inventory.warehouseId == command.warehouseId &&
                inventory.productId == command.productId &&
                inventory.qualityStatus == command.qualityStatus &&
                lot.lotNumber == command.lotNumber
        if (!sameRequest) throw IdempotencyKeyConflictException()
        return result(inventory, inventory.lotId, history)
    }

    private fun result(
        inventory: Inventory,
        lotId: Long,
        history: InventoryHistory,
    ): ReturnResult =
        ReturnResult(
            inventoryId = checkNotNull(inventory.inventoryId) { "저장된 재고는 식별자가 있어야 한다" },
            warehouseId = inventory.warehouseId,
            productId = inventory.productId,
            lotId = lotId,
            qualityStatus = inventory.qualityStatus,
            quantityChange = history.quantityChange,
            quantityAfter = history.quantityAfter,
            inventoryHistoryId = checkNotNull(history.inventoryHistoryId) { "저장된 이력은 식별자가 있어야 한다" },
        )
}
