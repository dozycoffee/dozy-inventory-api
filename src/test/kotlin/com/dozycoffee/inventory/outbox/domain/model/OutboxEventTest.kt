package com.dozycoffee.inventory.outbox.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
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
    ): OutboxEvent = OutboxEvent.create(AggregateType.INVENTORY, aggregateId, eventType, partitionKey, payload)

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
}
