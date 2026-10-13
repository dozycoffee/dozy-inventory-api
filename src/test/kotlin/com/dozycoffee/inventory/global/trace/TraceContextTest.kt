package com.dozycoffee.inventory.global.trace

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.util.context.Context

class TraceContextTest {
    private val valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    private val format = Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")

    @Nested
    inner class `생성` {
        @Test
        fun `올바른 traceparent는 trace-id와 플래그를 잇고 span-id는 새로 만든다`() {
            val created: String = TraceContext.create("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00", "ignored")

            assertTrue(format.matches(created), created)
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", created.split("-")[1])
            assertNotEquals("00f067aa0ba902b7", created.split("-")[2])
            assertEquals("00", created.split("-")[3])
        }

        @Test
        fun `traceparent가 없으면 32자리 16진수 X-Trace-Id를 trace-id로 쓴다`() {
            val created: String = TraceContext.create(null, "ABCDEF0123456789abcdef0123456789")

            assertEquals("abcdef0123456789abcdef0123456789", created.split("-")[1])
            assertEquals("01", created.split("-")[3])
        }

        @Test
        fun `둘 다 쓸 수 없으면 새 trace-id를 만든다`() {
            listOf(
                TraceContext.create(null, null),
                TraceContext.create("garbage", "not-hex-trace-id"),
                TraceContext.create("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "short"),
            ).forEach { created ->
                assertTrue(format.matches(created), created)
            }
        }

        @Test
        fun `모두 0인 trace-id는 규격상 무효라 쓰지 않는다`() {
            val zero = "0".repeat(32)

            assertNotEquals(zero, TraceContext.create("00-$zero-00f067aa0ba902b7-01", null).split("-")[1])
            assertNotEquals(zero, TraceContext.create(null, zero).split("-")[1])
        }

        @Test
        fun `span-id는 호출마다 다르다`() {
            assertEquals(50, (1..50).map { TraceContext.create(valid, null).split("-")[2] }.toSet().size)
        }
    }

    @Nested
    inner class `전달` {
        @Test
        fun `Reactor Context에 실은 값을 코루틴에서 읽는다`() =
            runBlocking<Unit> {
                withContext(ReactorContext(TraceContext.contextOf(Context.empty(), valid))) {
                    assertEquals(valid, TraceContext.current())
                }
            }

        @Test
        fun `컨텍스트가 없거나 값이 없으면 null이다`() =
            runBlocking<Unit> {
                assertNull(TraceContext.current())
                withContext(ReactorContext(Context.empty())) { assertNull(TraceContext.current()) }
            }
    }
}
