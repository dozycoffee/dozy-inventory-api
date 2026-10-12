package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmDisposalUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmDisposalCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.DisposalResult
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
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotFoundException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 폐기 확정 반영(ADR-0028). 수량·이력·이벤트는 한 트랜잭션으로 저장한다. 폐기는 가용 수량 안에서 총 수량을 줄이는 조건부 UPDATE이고
 * 정상 품질 재고는 거부한다. 같은 멱등 키가 동시에 들어오면 늦은 쪽의 이력 저장이 중복 키로 실패해 롤백되므로(수량 차감도 되돌아간다)
 * 트랜잭션 밖에서 저장된 이력으로 이전 결과를 반환한다.
 */
@Service
class DisposalService(
    private val lotRepository: LotRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
    private val transactionalOperator: TransactionalOperator,
) : ConfirmDisposalUseCase {
    override suspend fun confirm(command: ConfirmDisposalCommand): DisposalResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        validate(command)

        findPreviousResult(command, key)?.let { return it }
        return try {
            checkNotNull(transactionalOperator.executeAndAwait { dispose(command, key) }) { "폐기 반영 결과가 없다" }
        } catch (e: DuplicateIdempotencyKeyException) {
            findPreviousResult(command, key) ?: throw e
        }
    }

    private fun validate(command: ConfirmDisposalCommand) {
        Inventory.requireValidAmount(command.quantity)
        RequesterService.of(command.requesterService)
        if (!command.qualityStatus.isDisposable) throw InvalidDomainValueException(InventoryErrorCode.INVALID_DISPOSAL_QUALITY_STATUS)
    }

    private suspend fun dispose(
        command: ConfirmDisposalCommand,
        key: IdempotencyKey,
    ): DisposalResult {
        val lot: Lot = lotRepository.findByProductIdAndLotNumber(command.productId, command.lotNumber) ?: throw LotNotFoundException()
        val lotId: Long = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" }
        val row: Inventory =
            inventoryRepository.findByKey(InventoryKey(command.warehouseId, lotId, command.qualityStatus))
                ?: throw InventoryNotFoundException()
        val inventoryId: Long = checkNotNull(row.inventoryId) { "저장된 재고는 식별자가 있어야 한다" }

        val inventory: Inventory = inventoryRepository.decrease(inventoryId, command.quantity)
        val history: InventoryHistory =
            inventoryHistoryRepository.save(
                InventoryHistory.create(
                    inventoryId = inventoryId,
                    historyType = HistoryType.DISPOSAL,
                    quantityChange = -command.quantity,
                    quantityAfter = inventory.quantity,
                    referenceType = ReferenceType.DISPOSAL_ITEM,
                    referenceId = command.referenceId,
                    idempotencyKey = key,
                    requesterService = RequesterService.of(command.requesterService),
                ),
            )
        inventoryEventPublisher.publish(
            InventoryEvent(
                eventType = InventoryEventType.DECREASED,
                inventoryId = inventoryId,
                warehouseId = command.warehouseId,
                productId = command.productId,
                lotId = lotId,
                qualityStatus = command.qualityStatus,
                quantityChange = -command.quantity,
                quantityAfter = inventory.quantity,
                referenceType = ReferenceType.DISPOSAL_ITEM,
                referenceId = command.referenceId,
                idempotencyKey = key.value,
            ),
        )
        return result(inventory, lotId, history)
    }

    /** 같은 멱등 키로 이미 처리했으면 저장된 이력에서 이전 결과를 다시 만든다. 내용이 다르면 거부한다 */
    private suspend fun findPreviousResult(
        command: ConfirmDisposalCommand,
        key: IdempotencyKey,
    ): DisposalResult? {
        val histories: List<InventoryHistory> = inventoryHistoryRepository.findAllByIdempotencyKey(key)
        if (histories.isEmpty()) return null
        val history: InventoryHistory = histories.singleOrNull() ?: throw IdempotencyKeyConflictException()

        val inventory: Inventory = checkNotNull(inventoryRepository.findById(history.inventoryId)) { "이력의 재고 행이 없다" }
        val lot: Lot = checkNotNull(lotRepository.findById(inventory.lotId)) { "재고 행의 Lot이 없다" }
        val sameRequest: Boolean =
            history.historyType == HistoryType.DISPOSAL &&
                history.referenceType == ReferenceType.DISPOSAL_ITEM &&
                history.referenceId == command.referenceId &&
                history.quantityChange == -command.quantity &&
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
    ): DisposalResult =
        DisposalResult(
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
