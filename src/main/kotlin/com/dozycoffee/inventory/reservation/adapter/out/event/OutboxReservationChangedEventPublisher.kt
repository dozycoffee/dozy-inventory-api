package com.dozycoffee.inventory.reservation.adapter.out.event

import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime

/**
 * 예약 변경 이벤트를 호출한 서비스의 트랜잭션 안에서 Outbox에 저장한다(ADR-0025). 한 예약이 여러 상품에 걸치므로
 * 파티션 키는 `창고ID:예약ID`이고 같은 예약의 이벤트는 저장 순서대로 발행된다.
 */
@Component
class OutboxReservationChangedEventPublisher(
    private val recordOutboxEventUseCase: RecordOutboxEventUseCase,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) : ReservationChangedEventPublisher {
    override suspend fun publish(event: ReservationChangedEvent) {
        val eventType: String = "RESERVATION_${event.changeType.name}"
        recordOutboxEventUseCase.record(
            RecordOutboxEventCommand(
                aggregateType = AGGREGATE_TYPE,
                aggregateId = event.reservationId,
                eventType = eventType,
                partitionKey = "${event.warehouseId}:${event.reservationId}",
                payload = jsonMapper.writeValueAsString(Payload.of(eventType, event, OffsetDateTime.now(clock))),
            ),
        )
    }

    /** 구독자에게 보이는 이벤트 본문. 필드 이름은 계약이라 함부로 바꾸지 않는다 */
    data class Payload(
        val eventType: String,
        val occurredAt: OffsetDateTime,
        val reservationId: Long,
        val warehouseId: Long,
        val channel: String,
        val externalOrderId: String,
        val items: List<Item>,
        val idempotencyKey: String,
    ) {
        data class Item(
            val productId: Long,
            val quantity: Int,
            val allocations: List<Allocation>,
        )

        data class Allocation(
            val inventoryId: Long,
            val quantity: Int,
        )

        companion object {
            fun of(
                eventType: String,
                event: ReservationChangedEvent,
                occurredAt: OffsetDateTime,
            ): Payload =
                Payload(
                    eventType,
                    occurredAt,
                    event.reservationId,
                    event.warehouseId,
                    event.channel,
                    event.externalOrderId,
                    event.items.map { item ->
                        Item(item.productId, item.quantity, item.allocations.map { Allocation(it.inventoryId, it.quantity) })
                    },
                    event.idempotencyKey,
                )
        }
    }

    private companion object {
        const val AGGREGATE_TYPE: String = "RESERVATION"
    }
}
