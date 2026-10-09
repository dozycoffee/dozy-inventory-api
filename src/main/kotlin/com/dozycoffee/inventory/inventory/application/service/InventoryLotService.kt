package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryLotsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryLotInfo
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import org.springframework.stereotype.Service

/** 호출한 서비스의 트랜잭션 안에서 실행하며 트랜잭션을 직접 열지 않는다 */
@Service
class InventoryLotService(
    private val inventoryRepository: InventoryRepository,
) : GetInventoryLotsUseCase {
    override suspend fun getLots(inventoryIds: Set<Long>): List<InventoryLotResult> {
        if (inventoryIds.isEmpty()) return emptyList()
        return inventoryRepository
            .findLotInfos(inventoryIds)
            .sortedBy(InventoryLotInfo::inventoryId)
            .map { InventoryLotResult(it.inventoryId, it.lotId, it.lotNumber, it.expirationDate) }
    }
}
