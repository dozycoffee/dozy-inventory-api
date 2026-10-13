package com.dozycoffee.inventory.inventory.adapter.out.event

import com.dozycoffee.inventory.inventory.application.port.out.LotExpiredEvent
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verifyBlocking
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class OutboxLotExpiredEventPublisherTest {
    @Mock
    private lateinit var recordOutboxEventUseCase: RecordOutboxEventUseCase

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: OutboxLotExpiredEventPublisher

    @BeforeEach
    fun setUp() {
        publisher =
            OutboxLotExpiredEventPublisher(
                recordOutboxEventUseCase,
                mapper,
            )
    }

    private suspend fun recorded(event: LotExpiredEvent): RecordOutboxEventCommand {
        publisher.publish(event)
        val command = argumentCaptor<RecordOutboxEventCommand>()
        verifyBlocking(recordOutboxEventUseCase) { record(command.capture()) }
        return command.firstValue
    }

    private val event: LotExpiredEvent =
        LotExpiredEvent(
            7L,
            100L,
            "LOT-A",
            LocalDate.of(2026, 10, 11),
            listOf(LotExpiredEvent.Transfer(10L, 5L, 8L, 70), LotExpiredEvent.Transfer(20L, 6L, 9L, 40)),
            listOf(3L, 9L),
        )

    @Test
    fun `Lot 집계의 이벤트로 Lot ID를 파티션 키로 저장한다`() =
        runBlocking<Unit> {
            val command: RecordOutboxEventCommand = recorded(event)

            assertEquals("LOT", command.aggregateType)
            assertEquals(7L, command.aggregateId)
            assertEquals("LOT_EXPIRED", command.eventType)
            assertEquals("7", command.partitionKey)
        }

    @Test
    fun `payload에 Lot 정보와 전환 내역과 확정 예약 ID를 담는다`() =
        runBlocking<Unit> {
            val json: JsonNode = mapper.readTree(recorded(event).payload)

            assertEquals(false, json.has("eventType"))
            assertEquals(false, json.has("occurredAt"))
            assertEquals(7L, json["lotId"].asLong())
            assertEquals(100L, json["productId"].asLong())
            assertEquals("LOT-A", json["lotNumber"].asString())
            assertEquals("2026-10-11", json["expirationDate"].asString())
            assertEquals(2, json["transfers"].size())
            assertEquals(10L, json["transfers"][0]["warehouseId"].asLong())
            assertEquals(5L, json["transfers"][0]["sourceInventoryId"].asLong())
            assertEquals(8L, json["transfers"][0]["disposalInventoryId"].asLong())
            assertEquals(70, json["transfers"][0]["quantity"].asInt())
            assertEquals(
                listOf(3L, 9L),
                (0 until json["confirmedReservationIds"].size()).map { json["confirmedReservationIds"][it].asLong() },
            )
        }

    @Test
    fun `전환이 없거나 유통기한이 없으면 빈 목록과 null로 담는다`() =
        runBlocking<Unit> {
            val json: JsonNode =
                mapper.readTree(
                    recorded(event.copy(expirationDate = null, transfers = emptyList(), confirmedReservationIds = emptyList())).payload,
                )

            assertTrue(json["expirationDate"].isNull)
            assertEquals(0, json["transfers"].size())
            assertEquals(0, json["confirmedReservationIds"].size())
        }
}
