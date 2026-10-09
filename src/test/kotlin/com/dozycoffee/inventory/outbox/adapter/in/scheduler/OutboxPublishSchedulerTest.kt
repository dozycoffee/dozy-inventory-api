package com.dozycoffee.inventory.outbox.adapter.`in`.scheduler

import com.dozycoffee.inventory.outbox.application.port.`in`.PublishOutboxEventsUseCase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class OutboxPublishSchedulerTest {
    @Mock
    private lateinit var publishOutboxEventsUseCase: PublishOutboxEventsUseCase

    private lateinit var scheduler: OutboxPublishScheduler

    @BeforeEach
    fun setUp() {
        scheduler = OutboxPublishScheduler(publishOutboxEventsUseCase, 3)
    }

    @Test
    fun `묶음 크기보다 적게 발행하면 한 번만 호출하고 끝낸다`() =
        runBlocking<Unit> {
            whenever(publishOutboxEventsUseCase.publishPending(3)).thenReturn(2)

            scheduler.publish()

            verifyBlocking(publishOutboxEventsUseCase, times(1)) { publishPending(3) }
        }

    @Test
    fun `묶음이 가득 차면 이어서 다음 묶음을 발행한다`() =
        runBlocking<Unit> {
            whenever(publishOutboxEventsUseCase.publishPending(3)).thenReturn(3).thenReturn(3).thenReturn(1)

            scheduler.publish()

            verifyBlocking(publishOutboxEventsUseCase, times(3)) { publishPending(3) }
        }

    @Test
    fun `한 번에 최대 10묶음까지만 발행한다`() =
        runBlocking<Unit> {
            whenever(publishOutboxEventsUseCase.publishPending(3)).thenReturn(3)

            scheduler.publish()

            verifyBlocking(publishOutboxEventsUseCase, times(10)) { publishPending(3) }
        }

    @Test
    fun `발행이 실패해도 예외를 밖으로 던지지 않는다`() =
        runBlocking<Unit> {
            whenever(publishOutboxEventsUseCase.publishPending(3)).thenThrow(IllegalStateException("db down"))

            scheduler.publish()

            verifyBlocking(publishOutboxEventsUseCase, times(1)) { publishPending(3) }
        }
}
