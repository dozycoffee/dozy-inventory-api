package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
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
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class OutboxCleanUpServiceTest {
    @Mock
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var service: OutboxCleanUpService

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 9, 12, 0)

    @BeforeEach
    fun setUp() {
        service = OutboxCleanUpService(outboxEventRepository, Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
    }

    @Nested
    inner class `삭제` {
        @Test
        fun `현재 시각에서 보관 기간을 뺀 시각 이전에 발행된 이벤트를 지우고 지운 개수를 반환한다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.deletePublishedBefore(LocalDateTime.of(2026, 10, 2, 12, 0), 500)).thenReturn(42)

                assertEquals(42, service.cleanUp(Duration.ofDays(7), 500))
            }

        @Test
        fun `보관 기간이 0이면 지금까지 발행된 이벤트를 모두 대상으로 한다`() =
            runBlocking<Unit> {
                whenever(outboxEventRepository.deletePublishedBefore(now, 10)).thenReturn(3)

                assertEquals(3, service.cleanUp(Duration.ZERO, 10))
            }
    }

    @Nested
    inner class `검증` {
        @Test
        fun `보관 기간이 음수이면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<IllegalArgumentException> { service.cleanUp(Duration.ofDays(-1), 10) }

                verifyNoInteractions(outboxEventRepository)
            }

        @Test
        fun `묶음 크기가 1 미만이거나 10000을 넘으면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<IllegalArgumentException> { service.cleanUp(Duration.ofDays(7), 0) }
                assertThrows<IllegalArgumentException> { service.cleanUp(Duration.ofDays(7), 10_001) }

                verifyBlocking(outboxEventRepository, org.mockito.kotlin.never()) { deletePublishedBefore(any(), any()) }
            }
    }
}
