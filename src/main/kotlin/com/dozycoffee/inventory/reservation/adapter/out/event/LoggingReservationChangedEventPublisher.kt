package com.dozycoffee.inventory.reservation.adapter.out.event

import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 이벤트를 저장하거나 전달하지 않고 로그만 남긴다. 구독자에게는 아무것도 발행되지 않는다 */
@Component
class LoggingReservationChangedEventPublisher : ReservationChangedEventPublisher {
    override suspend fun publish(event: ReservationChangedEvent) {
        log.info(
            "Reservation event: type={}, reservationId={}, warehouseId={}, channel={}, externalOrderId={}, items={}",
            event.changeType,
            event.reservationId,
            event.warehouseId,
            event.channel,
            event.externalOrderId,
            event.items.size,
        )
    }

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(LoggingReservationChangedEventPublisher::class.java)
    }
}
