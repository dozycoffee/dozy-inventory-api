package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.HoldInventoryAllocationUseCase
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 재고 행을 `inventory_id` 오름차순으로 보류해 동시 요청과 데드락이 나지 않게 한다. 트랜잭션을 직접 열지 않는다 */
@Service
class InventoryHoldService(
    private val inventoryRepository: InventoryRepository,
    private val clock: Clock,
) : HoldInventoryAllocationUseCase {
    override suspend fun hold(
        inventoryIds: Set<Long>,
        reason: String,
    ) {
        // 시각 컬럼이 마이크로초(DATETIME(6))라 먼저 잘라 둔다
        val now: LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        inventoryIds.sorted().forEach { inventoryRepository.holdAllocation(it, reason, now) }
    }
}
