package com.dozycoffee.inventory.outbox.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.eventIdOf
import com.dozycoffee.inventory.outbox.newEventId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class OutboxEventTest {
    private fun create(
        aggregateId: Long = 1L,
        eventType: String? = "INVENTORY_INCREASED",
        partitionKey: String? = "10:100",
        payload: String? = """{"a":1}""",
    ): OutboxEvent = OutboxEvent.create(AggregateType.INVENTORY, aggregateId, eventType, partitionKey, payload, newEventId())

    private fun assertRejected(build: () -> Any) {
        val e: InvalidDomainValueException = assertThrows { build() }
        assertEquals(OutboxErrorCode.INVALID_OUTBOX_EVENT, e.errorCode)
    }

    @Nested
    inner class `생성` {
        @Test
        fun `발행 대기 상태로 시도 횟수 0이고 식별자와 시각은 없다`() {
            val event: OutboxEvent = create()

            assertEquals(OutboxStatus.PENDING, event.status)
            assertEquals(0, event.attemptCount)
            assertNull(event.outboxEventId)
            assertNull(event.createdAt)
            assertNull(event.publishedAt)
            assertEquals("10:100", event.partitionKey)
        }

        @Test
        fun `집계 ID는 양수이다`() {
            assertRejected { create(aggregateId = 0L) }
        }

        @Test
        fun `이벤트 유형과 파티션 키는 비어 있거나 100자를 넘을 수 없다`() {
            listOf(null, "", " ", "E".repeat(101)).forEach { value ->
                assertRejected { create(eventType = value) }
                assertRejected { create(partitionKey = value) }
            }
            create(eventType = "E".repeat(100), partitionKey = "K".repeat(100))
        }

        @Test
        fun `payload는 비어 있을 수 없다`() {
            listOf(null, "", "  ").forEach { assertRejected { create(payload = it) } }
        }
    }

    @Nested
    inner class `이벤트 ID와 추적 컨텍스트` {
        private fun createWith(
            eventId: String = newEventId(),
            eventVersion: Int = 1,
            traceparent: String? = null,
        ): OutboxEvent = OutboxEvent.create(AggregateType.INVENTORY, 1L, "E", "10:100", "{}", eventId, eventVersion, traceparent)

        @Test
        fun `이벤트 ID와 버전과 추적 컨텍스트를 그대로 가진다`() {
            val id: String = newEventId()
            val trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

            val event: OutboxEvent = createWith(id, 2, trace)

            assertEquals(id, event.eventId)
            assertEquals(2, event.eventVersion)
            assertEquals(trace, event.traceparent)
            assertEquals(null, createWith().traceparent)
        }

        @Test
        fun `UUID 형식이 아닌 이벤트 ID는 거부한다`() {
            listOf(
                "",
                "1",
                "0199A3C2-8E5A-7F30-8C4B-9D1E2F6A0B73",
                "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b7",
                "0199a3c28e5a7f308c4b9d1e2f6a0b73",
            ).forEach { assertRejected { createWith(eventId = it) } }
        }

        @Test
        fun `버전은 1 이상이어야 한다`() {
            assertRejected { createWith(eventVersion = 0) }
        }

        @Test
        fun `W3C 형식이 아닌 traceparent는 거부한다`() {
            listOf("", "abc", "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "00-4bf92f35-00f067aa0ba902b7-01")
                .forEach { assertRejected { createWith(traceparent = it) } }
        }
    }

    @Nested
    inner class `동등성` {
        @Test
        fun `저장 전에는 같은 객체만 같고 저장된 뒤에는 식별자가 같으면 같다`() {
            val a: OutboxEvent = create()
            val b: OutboxEvent = create()
            assertEquals(a, a)
            assertEquals(false, a == b)

            fun restored(id: Long): OutboxEvent =
                OutboxEvent.reconstitute(
                    id,
                    eventIdOf(id),
                    AggregateType.INVENTORY,
                    1L,
                    "T",
                    "K",
                    "{}",
                    OutboxStatus.PENDING,
                    0,
                    LocalDateTime.of(2026, 10, 8, 9, 0),
                    null,
                )
            assertEquals(restored(1L), restored(1L))
            assertEquals(false, restored(1L) == restored(2L))
        }
    }

    @Nested
    inner class `실패 기록과 격리` {
        private val now: LocalDateTime = LocalDateTime.of(2026, 10, 12, 12, 0)

        private fun pending(attempts: Int = 0): OutboxEvent =
            OutboxEvent.reconstitute(
                1L,
                eventIdOf(1L),
                AggregateType.INVENTORY,
                1L,
                "E",
                "10:100",
                "{}",
                OutboxStatus.PENDING,
                attempts,
                now,
                null,
            )

        @Test
        fun `실패를 기록하면 시도 횟수가 늘고 마지막 오류가 남으며 임계치 전에는 발행 대기다`() {
            val event: OutboxEvent = pending()

            assertEquals(false, event.recordFailure("boom", 3, now))
            assertEquals(false, event.recordFailure("boom2", 3, now))

            assertEquals(2, event.attemptCount)
            assertEquals("boom2", event.lastError)
            assertEquals(OutboxStatus.PENDING, event.status)
            assertNull(event.deadAt)
        }

        @Test
        fun `임계치에 이르면 격리되고 시각이 남는다`() {
            val event: OutboxEvent = pending(attempts = 2)

            assertEquals(true, event.recordFailure("boom", 3, now))

            assertEquals(OutboxStatus.DEAD, event.status)
            assertEquals(3, event.attemptCount)
            assertEquals(now, event.deadAt)
        }

        @Test
        fun `임계치가 1이면 첫 실패에 격리된다`() {
            assertEquals(true, pending().recordFailure("boom", 1, now))
        }

        @Test
        fun `오류 메시지는 500자로 줄이고 없을 수도 있다`() {
            val long: OutboxEvent = pending()
            long.recordFailure("e".repeat(600), 5, now)
            val none: OutboxEvent = pending()
            none.recordFailure(null, 5, now)

            assertEquals(500, long.lastError?.length)
            assertNull(none.lastError)
        }

        @Test
        fun `임계치는 1 이상이어야 한다`() {
            assertRejected { pending().recordFailure("boom", 0, now) }
        }

        @Test
        fun `발행 대기가 아닌 이벤트는 실패를 기록할 수 없다`() {
            val dead: OutboxEvent = pending(attempts = 1).also { it.recordFailure("boom", 2, now) }
            val published: OutboxEvent =
                OutboxEvent.reconstitute(
                    1L,
                    eventIdOf(1L),
                    AggregateType.INVENTORY,
                    1L,
                    "E",
                    "10:100",
                    "{}",
                    OutboxStatus.PUBLISHED,
                    0,
                    now,
                    now,
                )

            assertThrows<InvalidOutboxStateException> { dead.recordFailure("boom", 3, now) }
            assertThrows<InvalidOutboxStateException> { published.recordFailure("boom", 3, now) }
        }

        @Test
        fun `격리된 이벤트를 재발행하면 발행 대기가 되고 시도 횟수와 오류와 격리 시각이 지워진다`() {
            val event: OutboxEvent = pending(attempts = 2)
            event.recordFailure("boom", 3, now)

            event.requeue()

            assertEquals(OutboxStatus.PENDING, event.status)
            assertEquals(0, event.attemptCount)
            assertNull(event.lastError)
            assertNull(event.deadAt)
        }

        @Test
        fun `격리되지 않은 이벤트는 재발행할 수 없다`() {
            assertThrows<InvalidOutboxStateException> { pending().requeue() }
            val published: OutboxEvent =
                OutboxEvent.reconstitute(
                    1L,
                    eventIdOf(1L),
                    AggregateType.INVENTORY,
                    1L,
                    "E",
                    "10:100",
                    "{}",
                    OutboxStatus.PUBLISHED,
                    0,
                    now,
                    now,
                )
            assertThrows<InvalidOutboxStateException> { published.requeue() }
        }
    }
}
