package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.application.port.`in`.result.PageResult
import com.dozycoffee.inventory.outbox.application.port.`in`.result.OutboxEventResult
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.exception.OutboxEventNotFoundException
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class OutboxDeadLetterServiceTest {
    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var service: OutboxDeadLetterService

    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 12, 10, 0)

    @BeforeEach
    fun setUp() {
        service = OutboxDeadLetterService(outboxEventRepository)
    }

    private fun event(
        id: Long,
        status: OutboxStatus = OutboxStatus.DEAD,
    ): OutboxEvent =
        OutboxEvent.reconstitute(
            id,
            AggregateType.INVENTORY,
            id,
            "INVENTORY_INCREASED",
            "10:100",
            "{}",
            status,
            if (status == OutboxStatus.DEAD) 20 else 0,
            at.minusHours(1),
            if (status == OutboxStatus.PUBLISHED) at else null,
            if (status == OutboxStatus.DEAD) "boom" else null,
            if (status == OutboxStatus.DEAD) at else null,
        )

    @Nested
    inner class `목록` {
        @Test
        fun `격리된 이벤트를 페이지 위치에 맞춰 조회해 결과로 만든다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findByStatus(OutboxStatus.DEAD, 10L, 5)).thenReturn(listOf(event(9L), event(8L)))
                whenever(outboxEventRepository.countByStatus(OutboxStatus.DEAD)).thenReturn(12L)

                val result: PageResult<OutboxEventResult> = service.search(2, 5)

                assertEquals(listOf(9L, 8L), result.items.map { it.outboxEventId })
                assertEquals("boom", result.items.first().lastError)
                assertEquals(12L, result.totalElements)
                assertEquals(3, result.totalPages)
            }

        @Test
        fun `페이지나 크기가 범위를 벗어나면 거부하고 조회하지 않는다`() {
            listOf(-1 to 20, 0 to 0, 0 to 101).forEach { (page, size) ->
                val e: InvalidDomainValueException = assertThrows { runBlocking { service.search(page, size) } }
                assertEquals(OutboxErrorCode.INVALID_OUTBOX_QUERY.code, e.errorCode.code)
            }
            verifyNoInteractions(outboxEventRepository)
        }
    }

    @Nested
    inner class `상세` {
        @Test
        fun `격리된 이벤트를 돌려준다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(event(1L))

                val result: OutboxEventResult = service.getById(1L)

                assertEquals(OutboxStatus.DEAD, result.status)
                assertEquals(20, result.attemptCount)
                assertEquals(at, result.deadAt)
            }

        @Test
        fun `없거나 격리 상태가 아니면 찾을 수 없다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(null)
                whenever(outboxEventRepository.findById(2L)).thenReturn(event(2L, OutboxStatus.PENDING))

                assertThrows<OutboxEventNotFoundException> { service.getById(1L) }
                assertThrows<OutboxEventNotFoundException> { service.getById(2L) }
            }
    }

    @Nested
    inner class `재발행` {
        @Test
        fun `격리된 이벤트를 발행 대기로 되돌려 저장하고 되돌린 결과를 돌려준다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(event(1L))
                whenever(outboxEventRepository.saveRequeue(any())).thenReturn(true)

                val result: OutboxEventResult = service.requeue(1L)

                assertEquals(OutboxStatus.PENDING, result.status)
                assertEquals(0, result.attemptCount)
                assertEquals(null, result.lastError)
                assertEquals(null, result.deadAt)
                verifyBlocking(outboxEventRepository) { saveRequeue(any()) }
            }

        @Test
        fun `없는 이벤트는 찾을 수 없다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(null)

                assertThrows<OutboxEventNotFoundException> { service.requeue(1L) }
                verifyBlocking(outboxEventRepository, never()) { saveRequeue(any()) }
            }

        @Test
        fun `격리 상태가 아니면 되돌리지 않는다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(event(1L, OutboxStatus.PENDING))
                whenever(outboxEventRepository.findById(2L)).thenReturn(event(2L, OutboxStatus.PUBLISHED))

                assertThrows<InvalidOutboxStateException> { service.requeue(1L) }
                assertThrows<InvalidOutboxStateException> { service.requeue(2L) }
                verifyBlocking(outboxEventRepository, never()) { saveRequeue(any()) }
            }

        @Test
        fun `읽은 뒤 다른 요청이 먼저 되돌렸으면 상태 오류다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.findById(1L)).thenReturn(event(1L))
                whenever(outboxEventRepository.saveRequeue(any())).thenReturn(false)

                assertThrows<InvalidOutboxStateException> { service.requeue(1L) }
            }
    }
}
