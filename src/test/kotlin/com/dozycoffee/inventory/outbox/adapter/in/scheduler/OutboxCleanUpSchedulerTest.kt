package com.dozycoffee.inventory.outbox.adapter.`in`.scheduler

import com.dozycoffee.inventory.outbox.application.port.`in`.CleanUpOutboxEventsUseCase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class OutboxCleanUpSchedulerTest {
    @Mock
    private lateinit var cleanUpOutboxEventsUseCase: CleanUpOutboxEventsUseCase

    private lateinit var scheduler: OutboxCleanUpScheduler

    private val retention: Duration = Duration.ofDays(7)

    @BeforeEach
    fun setUp() {
        scheduler = OutboxCleanUpScheduler(cleanUpOutboxEventsUseCase, retention, 3)
    }

    @Test
    fun `묶음 크기보다 적게 지우면 한 번만 호출하고 끝낸다`() =
        runBlocking<Unit> {
            whenever(cleanUpOutboxEventsUseCase.cleanUp(retention, 3)).thenReturn(2)

            scheduler.cleanUp()

            verifyBlocking(cleanUpOutboxEventsUseCase, times(1)) { cleanUp(retention, 3) }
        }

    @Test
    fun `묶음이 가득 차면 이어서 다음 묶음을 지운다`() =
        runBlocking<Unit> {
            whenever(cleanUpOutboxEventsUseCase.cleanUp(retention, 3)).thenReturn(3).thenReturn(3).thenReturn(0)

            scheduler.cleanUp()

            verifyBlocking(cleanUpOutboxEventsUseCase, times(3)) { cleanUp(retention, 3) }
        }

    @Test
    fun `한 번에 최대 10묶음까지만 지운다`() =
        runBlocking<Unit> {
            whenever(cleanUpOutboxEventsUseCase.cleanUp(retention, 3)).thenReturn(3)

            scheduler.cleanUp()

            verifyBlocking(cleanUpOutboxEventsUseCase, times(10)) { cleanUp(retention, 3) }
        }

    @Test
    fun `삭제가 실패해도 예외를 밖으로 던지지 않는다`() =
        runBlocking<Unit> {
            whenever(cleanUpOutboxEventsUseCase.cleanUp(retention, 3)).thenThrow(IllegalStateException("db down"))

            scheduler.cleanUp()

            verifyBlocking(cleanUpOutboxEventsUseCase, times(1)) { cleanUp(retention, 3) }
        }
}
