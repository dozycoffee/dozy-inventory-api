package com.dozycoffee.inventory.reservation.adapter.out.event

import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.verifyBlocking
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class OutboxReservationChangedEventPublisherTest {
    @Mock
    private lateinit var recordOutboxEventUseCase: RecordOutboxEventUseCase

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: OutboxReservationChangedEventPublisher

    @BeforeEach
    fun setUp() {
        publisher =
            OutboxReservationChangedEventPublisher(
                recordOutboxEventUseCase,
                mapper,
                Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
    }

    private fun event(type: ReservationChangeType = ReservationChangeType.CONFIRMED): ReservationChangedEvent =
        ReservationChangedEvent(
            type,
            7L,
            10L,
            "OMS",
            "ORDER-1",
            listOf(
                ReservationChangedEvent.Item(
                    100L,
                    10,
                    listOf(ReservationChangedEvent.Allocation(5L, 6), ReservationChangedEvent.Allocation(7L, 4)),
                ),
                ReservationChangedEvent.Item(101L, 3, listOf(ReservationChangedEvent.Allocation(9L, 3))),
            ),
            "svc-oms-order-1",
        )

    private suspend fun recorded(event: ReservationChangedEvent): RecordOutboxEventCommand {
        publisher.publish(event)
        val command = argumentCaptor<RecordOutboxEventCommand>()
        verifyBlocking(recordOutboxEventUseCase, atLeastOnce()) { record(command.capture()) }
        return command.lastValue
    }

    @Test
    fun `예약 집계의 이벤트로 창고와 예약을 파티션 키로 저장한다`() =
        runBlocking<Unit> {
            val command: RecordOutboxEventCommand = recorded(event())

            assertEquals("RESERVATION", command.aggregateType)
            assertEquals(7L, command.aggregateId)
            assertEquals("RESERVATION_CONFIRMED", command.eventType)
            assertEquals("10:7", command.partitionKey)
        }

    @Test
    fun `payload에 상품별 수량과 재고 행별 할당 내역을 담는다`() =
        runBlocking<Unit> {
            val json: JsonNode = mapper.readTree(recorded(event()).payload)

            assertEquals("RESERVATION_CONFIRMED", json["eventType"].asString())
            assertEquals(7L, json["reservationId"].asLong())
            assertEquals(10L, json["warehouseId"].asLong())
            assertEquals("OMS", json["channel"].asString())
            assertEquals("ORDER-1", json["externalOrderId"].asString())
            assertEquals(2, json["items"].size())
            assertEquals(100L, json["items"][0]["productId"].asLong())
            assertEquals(10, json["items"][0]["quantity"].asInt())
            val allocations: JsonNode = json["items"][0]["allocations"]
            assertEquals(2, allocations.size())
            assertEquals(5L, allocations[0]["inventoryId"].asLong())
            assertEquals(6, allocations[0]["quantity"].asInt())
            assertEquals(7L, allocations[1]["inventoryId"].asLong())
            assertEquals(4, allocations[1]["quantity"].asInt())
            assertEquals(9L, json["items"][1]["allocations"][0]["inventoryId"].asLong())
            assertEquals("svc-oms-order-1", json["idempotencyKey"].asString())
            assertEquals("2026-10-08T12:00:00+09:00", json["occurredAt"].asString())
        }

    @Test
    fun `모든 변경 유형을 RESERVATION 접두사로 저장한다`() =
        runBlocking<Unit> {
            ReservationChangeType.entries.forEach { type ->
                assertEquals("RESERVATION_${type.name}", recorded(event(type)).eventType)
            }
        }
}
