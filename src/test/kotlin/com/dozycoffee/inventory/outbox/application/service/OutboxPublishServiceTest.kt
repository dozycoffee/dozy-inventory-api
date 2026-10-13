package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.application.port.out.OutboxMessagePublisher
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import com.dozycoffee.inventory.outbox.eventIdOf
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
import org.mockito.kotlin.eq
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
            eventIdOf(id),
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

                assertEquals(3, service.publishPending(100, 20))

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

                assertEquals(0, service.publishPending(100, 20))

                verifyBlocking(outboxMessagePublisher, never()) { publish(any()) }
                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }

        @Test
        fun `조회할 때 묶음 크기를 넘긴다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(7)).thenReturn(emptyList())

                service.publishPending(7, 20)

                verifyBlocking(outboxEventRepository) { findPending(7) }
            }
    }

    @Nested
    inner class `락` {
        @Test
        fun `다른 인스턴스가 락을 잡고 있으면 조회도 발행도 하지 않고 0을 반환하며 락을 놓지도 않는다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.tryAcquirePublishLock()).thenReturn(false)

                assertEquals(0, service.publishPending(100, 20))

                verifyBlocking(outboxEventRepository, never()) { findPending(any()) }
                verifyBlocking(outboxMessagePublisher, never()) { publish(any()) }
                verifyBlocking(outboxEventRepository, never()) { releasePublishLock() }
            }

        @Test
        fun `조회가 실패해도 락은 반드시 놓는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenThrow(IllegalStateException("db down"))

                assertThrows<IllegalStateException> { service.publishPending(100, 20) }

                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }
    }

    @Nested
    inner class `실패` {
        private fun pending(
            id: Long,
            key: String = "10:100",
            type: AggregateType = AggregateType.INVENTORY,
            attempts: Int = 0,
        ): OutboxEvent =
            OutboxEvent.reconstitute(
                id,
                eventIdOf(id),
                type,
                id,
                "EVENT",
                key,
                "{}",
                OutboxStatus.PENDING,
                attempts,
                now.minusMinutes(1),
                null,
            )

        private suspend fun failFor(vararg ids: Long) {
            whenever(outboxMessagePublisher.publish(any())).thenAnswer { invocation ->
                if (checkNotNull(invocation.getArgument<OutboxEvent>(0).outboxEventId) in
                    ids
                ) {
                    throw IllegalStateException("record too large")
                }
            }
        }

        private suspend fun savedFailures(): List<OutboxEvent> {
            val saved = argumentCaptor<OutboxEvent>()
            verifyBlocking(outboxEventRepository, org.mockito.kotlin.atLeast(0)) { saveFailure(saved.capture()) }
            return saved.allValues
        }

        @Test
        fun `같은 토픽의 다른 이벤트가 성공하면 실패한 이벤트의 시도 횟수와 마지막 오류를 저장한다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(pending(1L, "10:100"), pending(2L, "10:200")))
                failFor(1L)
                whenever(outboxEventRepository.saveFailure(any())).thenReturn(true)

                assertEquals(1, service.publishPending(100, 20))

                val failure: OutboxEvent = savedFailures().single()
                assertEquals(1L, failure.outboxEventId)
                assertEquals(1, failure.attemptCount)
                assertEquals(OutboxStatus.PENDING, failure.status)
                assertEquals("IllegalStateException: record too large", failure.lastError)
                verifyBlocking(outboxEventRepository) { markPublished(2L, now.plusNanos(123_456_000)) }
                verifyBlocking(outboxEventRepository, never()) { markPublished(eq(1L), any()) }
                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }

        @Test
        fun `실패한 이벤트와 같은 키의 뒤 이벤트는 건너뛰고 다른 키는 계속 발행한다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100))
                    .thenReturn(listOf(pending(1L, "10:100"), pending(2L, "10:100"), pending(3L, "10:300"), pending(4L, "10:100")))
                failFor(1L)
                whenever(outboxEventRepository.saveFailure(any())).thenReturn(true)

                assertEquals(1, service.publishPending(100, 20))

                val published = argumentCaptor<OutboxEvent>()
                verifyBlocking(outboxMessagePublisher, org.mockito.kotlin.times(2)) { publish(published.capture()) }
                assertEquals(listOf(1L, 3L), published.allValues.map { it.outboxEventId })
                verifyBlocking(outboxEventRepository) { markPublished(eq(3L), any()) }
                assertEquals(listOf(1L), savedFailures().map { it.outboxEventId })
            }

        @Test
        fun `시도 횟수가 임계치에 이르면 격리 상태와 시각을 저장한다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(
                    outboxEventRepository.findPending(100),
                ).thenReturn(listOf(pending(1L, "10:100", attempts = 19), pending(2L, "10:200")))
                failFor(1L)
                whenever(outboxEventRepository.saveFailure(any())).thenReturn(true)

                service.publishPending(100, 20)

                val dead: OutboxEvent = savedFailures().single()
                assertEquals(OutboxStatus.DEAD, dead.status)
                assertEquals(20, dead.attemptCount)
                assertEquals(now.plusNanos(123_456_000), dead.deadAt)
            }

        @Test
        fun `같은 토픽의 이벤트가 하나도 성공하지 못하면 공통 문제로 보고 시도 횟수를 세지 않는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(pending(1L, "10:100", attempts = 19)))
                failFor(1L)

                assertEquals(0, service.publishPending(100, 20))

                verifyBlocking(outboxEventRepository, never()) { saveFailure(any()) }
                verifyBlocking(outboxEventRepository, never()) { markPublished(any(), any()) }
                verifyBlocking(outboxEventRepository) { releasePublishLock() }
            }

        @Test
        fun `다른 토픽의 이벤트가 성공해도 실패한 토픽은 공통 문제로 보고 세지 않는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100))
                    .thenReturn(listOf(pending(1L, "10:100", AggregateType.LOT), pending(2L, "10:200", AggregateType.INVENTORY)))
                failFor(1L)

                assertEquals(1, service.publishPending(100, 20))

                verifyBlocking(outboxEventRepository, never()) { saveFailure(any()) }
            }

        @Test
        fun `연속으로 5건이 실패하면 나머지는 시도하지 않고 다음 주기로 넘긴다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn((1L..8L).map { pending(it, "10:$it") })
                whenever(outboxMessagePublisher.publish(any())).thenThrow(IllegalStateException("broker down"))

                assertEquals(0, service.publishPending(100, 20))

                verifyBlocking(outboxMessagePublisher, org.mockito.kotlin.times(5)) { publish(any()) }
                verifyBlocking(outboxEventRepository, never()) { saveFailure(any()) }
            }

        @Test
        fun `성공이 끼면 연속 실패가 끊겨 끝까지 시도하고 성공한 토픽의 실패만 센다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn((1L..8L).map { pending(it, "10:$it") })
                failFor(1L, 2L, 3L, 4L, 6L, 7L, 8L)
                whenever(outboxEventRepository.saveFailure(any())).thenReturn(true)

                assertEquals(1, service.publishPending(100, 20))

                verifyBlocking(outboxMessagePublisher, org.mockito.kotlin.times(8)) { publish(any()) }
                assertEquals(listOf(1L, 2L, 3L, 4L, 6L, 7L, 8L), savedFailures().map { it.outboxEventId })
            }

        @Test
        fun `그 사이 다른 처리로 상태가 바뀌어 실패 저장이 밀려도 예외 없이 끝난다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(pending(1L, "10:100"), pending(2L, "10:200")))
                failFor(1L)
                whenever(outboxEventRepository.saveFailure(any())).thenReturn(false)

                assertEquals(1, service.publishPending(100, 20))
            }

        @Test
        fun `실패한 이벤트는 발행 완료로 표시하지 않는다`() =
            runBlocking<Unit> {
                lockHeld()
                whenever(outboxEventRepository.findPending(100)).thenReturn(listOf(pending(1L)))
                failFor(1L)

                assertEquals(0, service.publishPending(100, 20))

                verifyBlocking(outboxEventRepository, never()) { markPublished(any(), any()) }
            }
    }

    @Nested
    inner class `입력 검증` {
        @Test
        fun `묶음 크기는 1 이상 1000 이하이고 격리 전 시도 횟수는 1 이상이어야 한다`() =
            runBlocking<Unit> {
                assertThrows<IllegalArgumentException> { service.publishPending(0, 20) }
                assertThrows<IllegalArgumentException> { service.publishPending(1001, 20) }
                assertThrows<IllegalArgumentException> { service.publishPending(100, 0) }
                verifyBlocking(outboxEventRepository, never()) { tryAcquirePublishLock() }
            }
    }
}
