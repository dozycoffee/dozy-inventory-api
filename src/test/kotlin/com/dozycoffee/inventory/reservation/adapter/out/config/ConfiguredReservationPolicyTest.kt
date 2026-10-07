package com.dozycoffee.inventory.reservation.adapter.out.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ConfiguredReservationPolicyTest {
    private val policy: ConfiguredReservationPolicy =
        ConfiguredReservationPolicy(
            ReservationProperties(ReservationProperties.MaxTtl(Duration.ofHours(1), mapOf("STORE" to Duration.ofHours(48)))),
        )

    @Test
    fun `설정된 채널은 채널 상한을 쓰고 그 외 채널은 기본값을 쓴다`() {
        assertEquals(Duration.ofHours(48), policy.maxTtlFor("STORE"))
        assertEquals(Duration.ofHours(1), policy.maxTtlFor("OMS"))
    }
}
