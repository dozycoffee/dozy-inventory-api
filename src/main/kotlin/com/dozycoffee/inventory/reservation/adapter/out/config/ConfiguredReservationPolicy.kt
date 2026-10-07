package com.dozycoffee.inventory.reservation.adapter.out.config

import com.dozycoffee.inventory.reservation.application.port.out.ReservationPolicy
import org.springframework.stereotype.Component
import java.time.Duration

/** 설정(`inventory.reservation.max-ttl`)에서 읽은 값으로 정책을 정한다 */
@Component
class ConfiguredReservationPolicy(
    private val properties: ReservationProperties,
) : ReservationPolicy {
    override fun maxTtlFor(channel: String): Duration = properties.maxTtl.forChannel(channel)
}
