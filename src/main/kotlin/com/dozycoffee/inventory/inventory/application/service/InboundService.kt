package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmInboundUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateLotException
import com.dozycoffee.inventory.inventory.domain.exception.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.LotMismatchException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.IdempotencyKey
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDate

/**
 * 입고 확정 반영. 수량·이력·이벤트는 한 트랜잭션으로 저장하고, 트랜잭션 밖에서 두 가지 경합을 처리한다.
 * 같은 멱등 키가 동시에 들어오면 늦은 쪽이 중복 키로 롤백되므로 저장된 이력으로 이전 결과를 반환하고,
 * 같은 Lot이 동시에 처음 들어오면 늦은 쪽의 Lot 저장이 중복으로 실패하므로 한 번 다시 시도한다.
 */
@Service
class InboundService(
    private val getProductUseCase: GetProductUseCase,
    private val lotRepository: LotRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventoryHistoryRepository: InventoryHistoryRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
    @Value("\${ims.inventory.expiring-soon-days}") private val expiringSoonDays: Int,
) : ConfirmInboundUseCase {
    override suspend fun confirm(command: ConfirmInboundCommand): InboundResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        validate(command)

        findPreviousResult(command, key)?.let { return it }
        return try {
            applyOnce(command, key)
        } catch (e: DuplicateLotException) {
            applyOnce(command, key)
        }
    }

    private fun validate(command: ConfirmInboundCommand) {
        Inventory.requireValidAmount(command.quantity)
        if (!command.qualityStatus.isInspectionResult) {
            throw InvalidDomainValueException(InventoryErrorCode.INVALID_INBOUND_QUALITY_STATUS)
        }
    }

    private suspend fun applyOnce(
        command: ConfirmInboundCommand,
        key: IdempotencyKey,
    ): InboundResult =
        try {
            checkNotNull(transactionalOperator.executeAndAwait { receive(command, key) }) { "입고 반영 결과가 없다" }
        } catch (e: DuplicateIdempotencyKeyException) {
            findPreviousResult(command, key) ?: throw e
        }

    private suspend fun receive(
        command: ConfirmInboundCommand,
        key: IdempotencyKey,
    ): InboundResult {
        getProductUseCase.getById(command.productId)
        val lot: Lot = findOrRegisterLot(command)
        val lotId: Long = checkNotNull(lot.lotId) { "저장된 Lot은 식별자가 있어야 한다" }

        val inventory: Inventory =
            inventoryRepository.increase(
                InventoryKey(command.warehouseId, lotId, command.qualityStatus),
                command.productId,
                command.quantity,
            )
        val history: InventoryHistory =
            inventoryHistoryRepository.save(
                InventoryHistory.create(
                    inventoryId = checkNotNull(inventory.inventoryId) { "저장된 재고는 식별자가 있어야 한다" },
                    historyType = HistoryType.INBOUND,
                    quantityChange = command.quantity,
                    quantityAfter = inventory.quantity,
                    referenceType = ReferenceType.INBOUND_ITEM,
                    referenceId = command.referenceId,
                    idempotencyKey = key,
                    requesterService = command.requesterService,
                ),
            )
        inventoryEventPublisher.publish(
            InventoryEvent(
                eventType = InventoryEventType.INCREASED,
                warehouseId = command.warehouseId,
                productId = command.productId,
                lotId = lotId,
                qualityStatus = command.qualityStatus,
                quantityChange = command.quantity,
                quantityAfter = inventory.quantity,
                referenceType = ReferenceType.INBOUND_ITEM,
                referenceId = command.referenceId,
                idempotencyKey = key.value,
            ),
        )
        return result(inventory, lotId, history)
    }

    /** 상품과 Lot 번호로 Lot을 찾아 재사용하고 없으면 등록한다. 제조일자나 유통기한이 저장된 값과 다르면 거부한다 */
    private suspend fun findOrRegisterLot(command: ConfirmInboundCommand): Lot {
        val existing: Lot? = lotRepository.findByProductIdAndLotNumber(command.productId, command.lotNumber)
        if (existing != null) {
            if (!existing.hasSameDates(command.manufactureDate, command.expirationDate)) throw LotMismatchException()
            return existing
        }
        return lotRepository.save(
            Lot.create(
                productId = command.productId,
                lotNumber = command.lotNumber,
                manufactureDate = command.manufactureDate,
                expirationDate = command.expirationDate,
                today = LocalDate.now(clock),
                expiringSoonDays = expiringSoonDays,
            ),
        )
    }

    /** 같은 멱등 키로 이미 처리했으면 저장된 이력에서 이전 결과를 다시 만든다. 내용이 다르면 거부한다 */
    private suspend fun findPreviousResult(
        command: ConfirmInboundCommand,
        key: IdempotencyKey,
    ): InboundResult? {
        val histories: List<InventoryHistory> = inventoryHistoryRepository.findAllByIdempotencyKey(key)
        if (histories.isEmpty()) return null
        val history: InventoryHistory = histories.singleOrNull() ?: throw IdempotencyKeyConflictException()

        val inventory: Inventory = checkNotNull(inventoryRepository.findById(history.inventoryId)) { "이력의 재고 행이 없다" }
        val lot: Lot = checkNotNull(lotRepository.findById(inventory.lotId)) { "재고 행의 Lot이 없다" }
        val sameRequest: Boolean =
            history.historyType == HistoryType.INBOUND &&
                history.referenceType == ReferenceType.INBOUND_ITEM &&
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
    ): InboundResult =
        InboundResult(
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
