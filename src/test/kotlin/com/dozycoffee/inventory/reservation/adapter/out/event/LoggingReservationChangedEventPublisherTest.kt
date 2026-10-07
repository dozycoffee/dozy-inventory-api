package com.dozycoffee.inventory.reservation.adapter.out.event

import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingReservationChangedEventPublisherTest {
    @Test
    fun `이벤트를 발행해도 예외 없이 끝난다`() =
        runBlocking<Unit> {
            val event: ReservationChangedEvent =
                ReservationChangedEvent(
                    ReservationChangeType.CREATED,
                    1L,
                    10L,
                    "OMS",
                    "ORDER-1",
                    listOf(ReservationChangedEvent.Item(100L, 5)),
                    "key",
                )

            LoggingReservationChangedEventPublisher().publish(event)
        }
}
