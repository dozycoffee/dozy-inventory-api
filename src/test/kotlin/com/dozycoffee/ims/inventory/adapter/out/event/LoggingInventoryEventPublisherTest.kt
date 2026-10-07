package com.dozycoffee.ims.inventory.adapter.out.event

import com.dozycoffee.ims.inventory.application.port.out.InventoryEvent
import com.dozycoffee.ims.inventory.application.port.out.InventoryEventType
import com.dozycoffee.ims.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.ims.inventory.domain.enumeration.ReferenceType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingInventoryEventPublisherTest {
    @Test
    fun `이벤트를 발행해도 예외 없이 끝난다`() =
        runBlocking<Unit> {
            val event: InventoryEvent =
                InventoryEvent(
                    InventoryEventType.INCREASED,
                    10L,
                    100L,
                    1000L,
                    QualityStatus.NORMAL,
                    5,
                    15,
                    ReferenceType.INBOUND_ITEM,
                    77L,
                    "key",
                )

            LoggingInventoryEventPublisher().publish(event)
        }
}
