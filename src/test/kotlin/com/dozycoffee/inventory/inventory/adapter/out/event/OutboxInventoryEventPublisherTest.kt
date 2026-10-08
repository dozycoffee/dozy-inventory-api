package com.dozycoffee.inventory.inventory.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.out.InventoryEvent
import com.dozycoffee.inventory.inventory.application.port.out.InventoryEventType
import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verifyBlocking
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class OutboxInventoryEventPublisherTest {
    @Mock
    private lateinit var recordOutboxEventUseCase: RecordOutboxEventUseCase

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: OutboxInventoryEventPublisher

    @BeforeEach
    fun setUp() {
        publisher =
            OutboxInventoryEventPublisher(
                recordOutboxEventUseCase,
                mapper,
                Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
    }

    private fun event(type: InventoryEventType = InventoryEventType.INCREASED): InventoryEvent =
        InventoryEvent(
            type,
            500L,
            10L,
            100L,
            1000L,
            QualityStatus.NORMAL,
            if (type ==
                InventoryEventType.INCREASED
            ) {
                5
            } else {
                -5
            },
            15,
            ReferenceType.INBOUND_ITEM,
            77L,
            "wms-inbound-77",
        )

    private suspend fun recorded(event: InventoryEvent): RecordOutboxEventCommand {
        publisher.publish(event)
        val command = argumentCaptor<RecordOutboxEventCommand>()
        verifyBlocking(recordOutboxEventUseCase) { record(command.capture()) }
        return command.firstValue
    }

    @Test
    fun `재고 집계의 이벤트로 창고와 상품을 파티션 키로 저장한다`() =
        runBlocking<Unit> {
            val command: RecordOutboxEventCommand = recorded(event())

            assertEquals("INVENTORY", command.aggregateType)
            assertEquals(500L, command.aggregateId)
            assertEquals("INVENTORY_INCREASED", command.eventType)
            assertEquals("10:100", command.partitionKey)
        }

    @Test
    fun `payload에 구독자가 쓰는 모든 필드와 발생 시각을 담는다`() =
        runBlocking<Unit> {
            val json: JsonNode = mapper.readTree(recorded(event()).payload)

            assertEquals("INVENTORY_INCREASED", json["eventType"].asString())
            assertEquals(500L, json["inventoryId"].asLong())
            assertEquals(10L, json["warehouseId"].asLong())
            assertEquals(100L, json["productId"].asLong())
            assertEquals(1000L, json["lotId"].asLong())
            assertEquals("NORMAL", json["qualityStatus"].asString())
            assertEquals(5, json["quantityChange"].asInt())
            assertEquals(15, json["quantityAfter"].asInt())
            assertEquals("INBOUND_ITEM", json["referenceType"].asString())
            assertEquals(77L, json["referenceId"].asLong())
            assertEquals("wms-inbound-77", json["idempotencyKey"].asString())
            assertEquals("2026-10-08T12:00:00+09:00", json["occurredAt"].asString())
        }

    @Test
    fun `감소 이벤트는 유형과 음수 변동량을 담는다`() =
        runBlocking<Unit> {
            val command: RecordOutboxEventCommand = recorded(event(InventoryEventType.DECREASED))

            assertEquals("INVENTORY_DECREASED", command.eventType)
            assertEquals(-5, mapper.readTree(command.payload)["quantityChange"].asInt())
        }
}
