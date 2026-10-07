package com.dozycoffee.inventory.inventory.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 이벤트를 저장하거나 전달하지 않고 로그만 남긴다. 구독자에게는 아무것도 발행되지 않는다 */
@Component
class LoggingInventoryEventPublisher : InventoryEventPublisher {
    override suspend fun publish(event: InventoryEvent) {
        log.info(
            "Inventory event: type={}, warehouseId={}, productId={}, lotId={}, quantityChange={}, quantityAfter={}",
            event.eventType,
            event.warehouseId,
            event.productId,
            event.lotId,
            event.quantityChange,
            event.quantityAfter,
        )
    }

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(LoggingInventoryEventPublisher::class.java)
    }
}
