package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.runBlocking
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class OutboxServiceTest {
    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var service: OutboxService

    @BeforeEach
    fun setUp() {
        service = OutboxService(outboxEventRepository, Clock.fixed(Instant.parse("2026-10-13T01:00:00Z"), ZoneOffset.UTC))
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
            assertEquals("""{"a":1}""", saved.firstValue.payload)
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
            assertEquals(1, saved.firstValue.eventVersion)
        }

    @Test
    fun `명령의 이벤트 버전을 그대로 저장한다`() =
        runBlocking<Unit> {
            whenever(outboxEventRepository.save(any())).thenAnswer { it.getArgument<OutboxEvent>(0) }

            service.record(command().copy(eventVersion = 3))

            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository) { save(saved.capture()) }
            assertEquals(3, saved.firstValue.eventVersion)
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
