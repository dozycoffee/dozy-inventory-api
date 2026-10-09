package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class OutboxPublishServiceTest {
    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Mock
    private lateinit var outboxMessagePublisher: OutboxMessagePublisher

    private lateinit var service: OutboxPublishService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 8, 12, 0)

    @BeforeEach
    fun setUp() {
        service =
            OutboxPublishService(
                outboxEventRepository,
                outboxMessagePublisher,
                DirectTransactionalOperator(),
                Clock.fixed(now.plusNanos(123_456_789).toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            )
    }

    private fun event(id: Long): OutboxEvent =
        OutboxEvent.reconstitute(
            id,
            AggregateType.INVENTORY,
            id,
            "INVENTORY_INCREASED",
            "10:100",
            "{}",
            OutboxStatus.PENDING,
            0,
            now.minusMinutes(1),
            null,
        )

    private suspend fun lockHeld() {
        whenever(outboxEventRepository.tryAcquirePublishLock()).thenReturn(true)
    }

    @Nested
    inner class `발행` {
        @Test
        fun `대기 이벤트를 순서대로 발행하고 발행 완료로 표시한 뒤 개수를 반환한다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(event(1L), event(2L), event(3L)))

                assertEquals(3, service.publishPending(100))

                inOrder(outboxMessagePublisher, outboxEventRepository) {
                    verifyBlocking(outboxMessagePublisher) { publish(event(1L)) }
                    verifyBlocking(outboxEventRepository) { markPublished(1L, now.plusNanos(123_456_000)) }
                    verifyBlocking(outboxMessagePublisher) { publish(event(2L)) }
                    verifyBlocking(outboxEventRepository) { markPublished(2L, now.plusNanos(123_456_000)) }
                    verifyBlocking(outboxMessagePublisher) { publish(event(3L)) }
                    verifyBlocking(outboxEventRepository) { markPublished(3L, now.plusNanos(123_456_000)) }
                }
            }

        @Test
        fun `대기 이벤트가 없으면 아무것도 발행하지 않고 락을 놓는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(emptyList())

                assertEquals(0, service.publishPending(100))

                verifyBlocking(outboxMessagePublisher, never()) { publish(any()) }
                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }

        @Test
        fun `조회할 때 묶음 크기를 넘긴다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(7)).thenReturn(emptyList())

                service.publishPending(7)

                verifyBlocking(outboxEventRepository) { findPending(7) }
            }
    }

    @Nested
    inner class `락` {
        @Test
        fun `다른 인스턴스가 락을 잡고 있으면 조회도 발행도 하지 않고 0을 반환하며 락을 놓지도 않는다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.tryAcquirePublishLock()).thenReturn(false)

                assertEquals(0, service.publishPending(100))

                verifyBlocking(outboxEventRepository, never()) { findPending(any()) }
                verifyBlocking(outboxMessagePublisher, never()) { publish(any()) }
                verifyBlocking(outboxEventRepository, never()) { releasePublishLock() }
            }

        @Test
        fun `조회가 실패해도 락은 반드시 놓는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenThrow(IllegalStateException("db down"))

                assertThrows<IllegalStateException> { service.publishPending(100) }

                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `한 이벤트가 실패하면 시도 횟수만 늘리고 그 뒤 이벤트는 발행하지 않으며 앞서 발행한 개수를 반환한다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(event(1L), event(2L), event(3L)))
                whenever(outboxMessagePublisher.publish(any())).thenAnswer { invocation ->
                    if (invocation.getArgument<OutboxEvent>(0).outboxEventId == 2L) throw IllegalStateException("broker down")
                }

                assertEquals(1, service.publishPending(100))

                verifyBlocking(outboxEventRepository) { markPublished(1L, now.plusNanos(123_456_000)) }
                verifyBlocking(outboxEventRepository) { increaseAttemptCount(2L) }
                verifyBlocking(outboxMessagePublisher, never()) { publish(event(3L)) }
                val published = argumentCaptor<Long>()
                verifyBlocking(outboxEventRepository) { markPublished(published.capture(), any()) }
                assertEquals(listOf(1L), published.allValues)
                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }

        @Test
        fun `실패한 이벤트는 발행 완료로 표시하지 않는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(event(1L)))
                whenever(outboxMessagePublisher.publish(any())).thenThrow(IllegalStateException("broker down"))

                assertEquals(0, service.publishPending(100))

                verifyBlocking(outboxEventRepository, never()) { markPublished(any(), any()) }
            }
    }

    @Nested
    inner class `입력 검증` {
        @Test
        fun `묶음 크기는 1 이상 1000 이하여야 한다`() =
            runBlocking<Unit> {
                assertThrows<IllegalArgumentException> { service.publishPending(0) }
                assertThrows<IllegalArgumentException> { service.publishPending(1001) }
                verifyBlocking(outboxEventRepository, never()) { tryAcquirePublishLock() }
            }
    }
}
