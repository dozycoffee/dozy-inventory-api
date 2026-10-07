package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.valueobject.IdempotencyKey
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component

@Component
class InventoryHistoryPersistenceAdapter(
    private val inventoryHistoryR2dbcRepository: InventoryHistoryR2dbcRepository,
) : InventoryHistoryRepository {
    override suspend fun save(history: InventoryHistory): InventoryHistory {
        check(history.inventoryHistoryId == null) { "이력은 변경할 수 없어 새로 저장만 할 수 있다" }
        return translatingDuplicateKey(duplicate = { DuplicateIdempotencyKeyException() }) {
            inventoryHistoryR2dbcRepository.save(InventoryHistoryEntity.from(history)).toDomain()
        }
    }

    override suspend fun findByIdempotencyKey(
        idempotencyKey: IdempotencyKey,
        inventoryId: Long,
    ): InventoryHistory? = inventoryHistoryR2dbcRepository.findByIdempotencyKeyAndInventoryId(idempotencyKey.value, inventoryId)?.toDomain()

    override suspend fun findAllByIdempotencyKey(idempotencyKey: IdempotencyKey): List<InventoryHistory> =
        inventoryHistoryR2dbcRepository.findAllByIdempotencyKeyOrderByInventoryIdAsc(idempotencyKey.value).toList().map { it.toDomain() }
}
