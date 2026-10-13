package com.dozycoffee.inventory.global.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UuidV7Test {
    @Nested
    inner class `생성` {
        @Test
        fun `36자 소문자 UUID이고 버전 7과 RFC 변형 비트를 가진다`() {
            val id: String = UuidV7.generate(1_800_000_000_000L)

            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
            val parsed: UUID = UUID.fromString(id)
            assertEquals(7, parsed.version())
            assertEquals(2, parsed.variant())
        }

        @Test
        fun `상위 48비트가 밀리초 시각이다`() {
            val millis = 1_800_000_000_123L

            assertEquals(
                millis,
                UuidV7
                    .generate(millis)
                    .replace("-", "")
                    .substring(0, 12)
                    .toLong(16),
            )
        }

        @Test
        fun `나중 시각의 ID가 사전순으로 뒤다`() {
            val ids: List<String> = (0L until 50L).map { UuidV7.generate(1_800_000_000_000L + it) }

            assertEquals(ids.sorted(), ids)
        }

        @Test
        fun `같은 시각에도 무작위 부분이 달라 겹치지 않는다`() {
            assertEquals(1000, (1..1000).map { UuidV7.generate(1_800_000_000_000L) }.toSet().size)
        }

        @Test
        fun `48비트를 넘거나 음수인 시각은 거부한다`() {
            assertThrows<IllegalArgumentException> { UuidV7.generate(-1L) }
            assertThrows<IllegalArgumentException> { UuidV7.generate(1L shl 48) }
            UuidV7.generate((1L shl 48) - 1)
        }
    }
}
