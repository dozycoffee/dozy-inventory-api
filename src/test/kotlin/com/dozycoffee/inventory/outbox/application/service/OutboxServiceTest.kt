package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.trace.TraceContext
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import reactor.util.context.Context
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class OutboxServiceTest {
    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var service: OutboxService

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() {
        service =
            OutboxService(outboxEventRepository, Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.ofHours(9)), jsonMapper)
    }

    private fun command(aggregateType: String = "INVENTORY"): RecordOutboxEventCommand =
        RecordOutboxEventCommand(aggregateType, 5L, "INVENTORY_INCREASED", "10:100", """{"a":1}""")

    @Test
    fun `이벤트를 발행 대기로 저장한다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }

            service.record(command())

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository) { save(saved.capture()) }
            assertEquals(AggregateType.INVENTORY, saved.firstValue.aggregateType)
            assertEquals(5L, saved.firstValue.aggregateId)
            assertEquals("INVENTORY_INCREASED", saved.firstValue.eventType)
            assertEquals("10:100", saved.firstValue.partitionKey)
            assertEquals("""{"a":1}""", jsonMapper.readTree(saved.firstValue.payload)["data"].toString())
            assertEquals(OutboxStatus.PENDING, saved.firstValue.status)
        }

    @Test
    fun `이벤트 ID는 저장 시각을 담은 UUID v7이고 이벤트마다 다르며 기본 버전은 1이다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }

            service.record(command())
            service.record(command())

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository, org.mockito.kotlin.times(2)) { save(saved.capture()) }
            val ids: List<String> = saved.allValues.map { it.eventId }
            assertEquals(2, ids.toSet().size)
            val millis: Long = Instant.parse("2026-10-13T01:00:00Z").toEpochMilli()
            ids.forEach { id ->
                assertEquals('7', id[14])
                assertEquals(millis, id.replace("-", "").substring(0, 12).toLong(16))
            }
            ids.forEach { id -> assertEquals(id, jsonMapper.readTree(saved.allValues[ids.indexOf(id)].payload)["eventId"].asString()) }
        }

    @Test
    fun `요청 밖에서는 traceparent 없이 저장하고 요청 안에서는 컨텍스트의 traceparent를 envelope 최상위에 담는다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }
            val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

            service.record(command())
            withContext(ReactorContext(TraceContext.contextOf(Context.empty(), traceparent))) { service.record(command()) }

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository, org.mockito.kotlin.times(2)) { save(saved.capture()) }
            val outside: JsonNode = jsonMapper.readTree(saved.firstValue.payload)
            val inside: JsonNode = jsonMapper.readTree(saved.secondValue.payload)
            assertEquals(false, outside.has("traceparent"))
            assertEquals(traceparent, inside["traceparent"].asString())
        }

    @Test
    fun `payload는 공통 형식으로 감싸고 메타데이터를 모두 값에 담는다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }

            service.record(command().copy(eventVersion = 2))

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository) { save(saved.capture()) }
            val envelope: JsonNode = jsonMapper.readTree(saved.firstValue.payload)
            assertEquals(setOf("eventId", "eventType", "eventVersion", "occurredAt", "source", "data"), envelope.propertyNames().toSet())
            assertEquals(saved.firstValue.eventId, envelope["eventId"].asString())
            assertEquals("INVENTORY_INCREASED", envelope["eventType"].asString())
            assertEquals(2, envelope["eventVersion"].asInt())
            assertEquals("inventory", envelope["source"].asString())
            assertEquals("2026-10-13T10:00:00+09:00", envelope["occurredAt"].asString())
            assertEquals(1, envelope["data"]["a"].asInt())
        }

    @Test
    fun `값이 null인 필드는 중첩 객체와 배열 안까지 지우고 배열 원소와 빈 값은 그대로 둔다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }
            val payload = """{"a":null,"b":0,"c":"","d":[],"e":{"f":null,"g":1},"items":[{"x":null,"y":2},null,{"z":[{"w":null}]}]}"""

            service.record(command().copy(payload = payload))

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository) { save(saved.capture()) }
            val data: JsonNode = jsonMapper.readTree(saved.firstValue.payload)["data"]
            assertEquals("""{"b":0,"c":"","d":[],"e":{"g":1},"items":[{"y":2},null,{"z":[{}]}]}""", data.toString())
        }

    @Test
    fun `본문이 JSON 객체가 아니면 저장하지 않고 거부한다`() =
        runBlocking<Unit> {
            listOf("not json", "[1]", "\"x\"", "1", "null").forEach { payload ->
                val e: InvalidDomainValueException = assertThrows { runBlocking { service.record(command().copy(payload = payload)) } }
                assertEquals(OutboxErrorCode.INVALID_OUTBOX_EVENT, e.errorCode)
            }
            verifyBlocking(outboxEventRepository, never()) { save(any()) }
        }

    @Test
    fun `모든 집계 유형을 받는다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }

            AggregateType.entries.forEach { service.record(command(it.name)) }
        }

    @Test
    fun `알 수 없는 집계 유형은 저장하지 않고 거부한다`() =
        runBlocking<Unit> {
            val e: InvalidDomainValueException = assertThrows { runBlocking { service.record(command("UNKNOWN")) } }

            assertEquals(OutboxErrorCode.INVALID_OUTBOX_EVENT, e.errorCode)
            verifyBlocking(outboxEventRepository, never()) { save(any()) }
        }

    @Test
    fun `이벤트 내용이 잘못되면 저장하지 않고 거부한다`() =
        runBlocking<Unit> {
            assertThrows<InvalidDomainValueException> { runBlocking { service.record(command().copy(payload = " ")) } }

            verifyBlocking(outboxEventRepository, never()) { save(any()) }
        }
}
