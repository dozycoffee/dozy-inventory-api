package com.dozycoffee.inventory.reservation.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryLotsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class OutboxReservationChangedEventPublisherTest {
    @Mock
    private lateinit var recordOutboxEventUseCase: RecordOutboxEventUseCase

    @Mock
    private lateinit var getInventoryLotsUseCase: GetInventoryLotsUseCase

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: OutboxReservationChangedEventPublisher

    @BeforeEach
    fun setUp() {
        publisher =
            OutboxReservationChangedEventPublisher(
                recordOutboxEventUseCase,
                getInventoryLotsUseCase,
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

    private val lots: List<InventoryLotResult> =
        listOf(
            InventoryLotResult(5L, 50L, "LOT-A", LocalDate.of(2027, 1, 1)),
            InventoryLotResult(7L, 70L, "LOT-B", null),
            InventoryLotResult(9L, 90L, "LOT-C", LocalDate.of(2027, 6, 30)),
        )

    private suspend fun recorded(event: ReservationChangedEvent): RecordOutboxEventCommand {
        whenever(getInventoryLotsUseCase.getLots(setOf(5L, 7L, 9L))).thenReturn(lots)
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
    fun `할당 내역마다 Lot ID와 번호와 유통기한을 담고 유통기한이 없으면 null이다`() =
        runBlocking<Unit> {
            val json: JsonNode = mapper.readTree(recorded(event()).payload)

            val first: JsonNode = json["items"][0]["allocations"][0]
            assertEquals(50L, first["lotId"].asLong())
            assertEquals("LOT-A", first["lotNumber"].asString())
            assertEquals("2027-01-01", first["expirationDate"].asString())
            val noExpiry: JsonNode = json["items"][0]["allocations"][1]
            assertEquals("LOT-B", noExpiry["lotNumber"].asString())
            assertTrue(noExpiry["expirationDate"].isNull)
            assertEquals("2027-06-30", json["items"][1]["allocations"][0]["expirationDate"].asString())
        }

    @Test
    fun `할당된 재고 행의 Lot을 찾을 수 없으면 이벤트를 저장하지 않고 실패한다`() =
        runBlocking<Unit> {
            whenever(getInventoryLotsUseCase.getLots(setOf(5L, 7L, 9L))).thenReturn(lots.take(2))

            assertThrows<IllegalStateException> { publisher.publish(event()) }

            verifyNoInteractions(recordOutboxEventUseCase)
        }

    @Test
    fun `모든 변경 유형을 RESERVATION 접두사로 저장한다`() =
        runBlocking<Unit> {
            ReservationChangeType.entries.forEach { type ->
                assertEquals("RESERVATION_${type.name}", recorded(event(type)).eventType)
            }
        }
}
