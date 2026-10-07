package com.dozycoffee.inventory.reservation.adapter.out.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

/** 예약 업무 설정. 채널 값이 확정되어 있지 않아 채널별 상한은 설정으로만 둔다(ADR-0022) */
@ConfigurationProperties("inventory.reservation")
data class ReservationProperties(
    @DefaultValue val maxTtl: MaxTtl,
) {
    /** 예약 만료 시각의 상한(생성 시각 + TTL). 맵에 없는 채널은 [default]를 쓴다 */
    data class MaxTtl(
        @DefaultValue("1h") val default: Duration,
        val channels: Map<String, Duration> = emptyMap(),
    ) {
        fun forChannel(channel: String): Duration = channels[channel] ?: default
    }
}
