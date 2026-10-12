package com.dozycoffee.inventory.inventory.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.out.LotExpiredEvent
import com.dozycoffee.inventory.inventory.application.port.out.LotExpiredEventPublisher
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * `Lot 경과` 이벤트를 호출한 서비스의 트랜잭션 안에서 Outbox에 저장한다(ADR-0025, ADR-0027). 집계는 Lot이고 파티션 키는 Lot ID라
 * 같은 Lot의 이벤트는 저장 순서대로 발행된다.
 */
@Component
class OutboxLotExpiredEventPublisher(
    private val recordOutboxEventUseCase: RecordOutboxEventUseCase,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) : LotExpiredEventPublisher {
    override suspend fun publish(event: LotExpiredEvent) {
        recordOutboxEventUseCase.record(
            RecordOutboxEventCommand(
                aggregateType = AGGREGATE_TYPE,
                aggregateId = event.lotId,
                eventType = EVENT_TYPE,
                partitionKey = event.lotId.toString(),
                payload = jsonMapper.writeValueAsString(Payload.of(event, OffsetDateTime.now(clock))),
            ),
        )
    }

    /** 구독자에게 보이는 이벤트 본문. 필드 이름은 계약이라 함부로 바꾸지 않는다 */
    data class Payload(
        val eventType: String,
        val occurredAt: OffsetDateTime,
        val lotId: Long,
        val productId: Long,
        val lotNumber: String,
        val expirationDate: LocalDate?,
        val transfers: List<Transfer>,
        val confirmedReservationIds: List<Long>,
    ) {
        data class Transfer(
            val warehouseId: Long,
            val sourceInventoryId: Long,
            val disposalInventoryId: Long,
            val quantity: Int,
        )

        companion object {
            fun of(
                event: LotExpiredEvent,
                occurredAt: OffsetDateTime,
            ): Payload =
                Payload(
                    EVENT_TYPE,
                    occurredAt,
                    event.lotId,
                    event.productId,
                    event.lotNumber,
                    event.expirationDate,
                    event.transfers.map { Transfer(it.warehouseId, it.sourceInventoryId, it.disposalInventoryId, it.quantity) },
                    event.confirmedReservationIds,
                )
        }
    }

    private companion object {
        const val AGGREGATE_TYPE: String = "LOT"
        const val EVENT_TYPE: String = "LOT_EXPIRED"
    }
}
