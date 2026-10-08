package com.dozycoffee.inventory.product.adapter.out.event

import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.product.application.port.out.ProductEvent
import com.dozycoffee.inventory.product.application.port.out.ProductEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime

/** 상품 마스터 변경 이벤트를 호출한 서비스의 트랜잭션 안에서 Outbox에 저장한다(ADR-0025). 파티션 키는 상품 ID이고 구독자는 상품 스냅샷으로 사본을 갱신한다 */
@Component
class OutboxProductEventPublisher(
    private val recordOutboxEventUseCase: RecordOutboxEventUseCase,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) : ProductEventPublisher {
    override suspend fun publish(event: ProductEvent) {
        val eventType: String = "PRODUCT_${event.eventType.name}"
        recordOutboxEventUseCase.record(
            RecordOutboxEventCommand(
                aggregateType = AGGREGATE_TYPE,
                aggregateId = event.product.productId,
                eventType = eventType,
                partitionKey = event.product.productId.toString(),
                payload = jsonMapper.writeValueAsString(Payload.of(eventType, event, OffsetDateTime.now(clock))),
            ),
        )
    }

    /** 구독자에게 보이는 이벤트 본문. 필드 이름은 계약이라 함부로 바꾸지 않는다 */
    data class Payload(
        val eventType: String,
        val occurredAt: OffsetDateTime,
        val productId: Long,
        val productCode: String,
        val productName: String,
        val category: String,
        val unit: String,
        val shelfLifeDays: Int?,
        val productStatus: String,
    ) {
        companion object {
            fun of(
                eventType: String,
                event: ProductEvent,
                occurredAt: OffsetDateTime,
            ): Payload =
                Payload(
                    eventType,
                    occurredAt,
                    event.product.productId,
                    event.product.productCode,
                    event.product.productName,
                    event.product.category.name,
                    event.product.unit,
                    event.product.shelfLifeDays,
                    event.product.productStatus.name,
                )
        }
    }

    private companion object {
        const val AGGREGATE_TYPE: String = "PRODUCT"
    }
}
