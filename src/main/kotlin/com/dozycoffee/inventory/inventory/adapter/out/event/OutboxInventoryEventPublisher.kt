package com.dozycoffee.inventory.inventory.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventPublisher
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime

/**
 * 재고 변동 이벤트를 호출한 서비스의 트랜잭션 안에서 Outbox에 저장한다(ADR-0025). 파티션 키는 `창고ID:상품ID`라
 * 같은 재고의 변동은 저장 순서대로 발행된다. 구독자는 [Payload.quantityAfter]로 총 수량을 확인한다.
 */
@Component
class OutboxInventoryEventPublisher(
    private val recordOutboxEventUseCase: RecordOutboxEventUseCase,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) : InventoryEventPublisher {
    override suspend fun publish(event: InventoryEvent) {
        val eventType: String = "INVENTORY_${event.eventType.name}"
        recordOutboxEventUseCase.record(
            RecordOutboxEventCommand(
                aggregateType = AGGREGATE_TYPE,
                aggregateId = event.inventoryId,
                eventType = eventType,
                partitionKey = "${event.warehouseId}:${event.productId}",
                payload = jsonMapper.writeValueAsString(Payload.of(eventType, event, OffsetDateTime.now(clock))),
            ),
        )
    }

    /** 구독자에게 보이는 이벤트 본문. 필드 이름은 계약이라 함부로 바꾸지 않는다 */
    data class Payload(
        val eventType: String,
        val occurredAt: OffsetDateTime,
        val inventoryId: Long,
        val warehouseId: Long,
        val productId: Long,
        val lotId: Long,
        val qualityStatus: String,
        val quantityChange: Int,
        val quantityAfter: Int,
        val referenceType: String,
        val referenceId: Long,
        val idempotencyKey: String,
    ) {
        companion object {
            fun of(
                eventType: String,
                event: InventoryEvent,
                occurredAt: OffsetDateTime,
            ): Payload =
                Payload(
                    eventType,
                    occurredAt,
                    event.inventoryId,
                    event.warehouseId,
                    event.productId,
                    event.lotId,
                    event.qualityStatus.name,
                    event.quantityChange,
                    event.quantityAfter,
                    event.referenceType.name,
                    event.referenceId,
                    event.idempotencyKey,
                )
        }
    }

    private companion object {
        const val AGGREGATE_TYPE: String = "INVENTORY"
    }
}
