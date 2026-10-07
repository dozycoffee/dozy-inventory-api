package com.dozycoffee.inventory.reservation.adapter.out.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class ReservationPropertiesTest {
    private fun bind(vararg entries: Pair<String, String>): ReservationProperties =
        Binder(MapConfigurationPropertySource(entries.toMap()))
            .bind("inventory.reservation", ReservationProperties::class.java)
            .orElseGet { ReservationProperties(ReservationProperties.MaxTtl(Duration.ofHours(1))) }

    @Test
    fun `채널별 상한이 없으면 기본값을 쓴다`() {
        val properties: ReservationProperties = bind("inventory.reservation.max-ttl.default" to "2h")

        assertEquals(Duration.ofHours(2), properties.maxTtl.forChannel("OMS"))
    }

    @Test
    fun `맵에 있는 채널은 채널 상한을 쓰고 없는 채널은 기본값을 쓴다`() {
        val properties: ReservationProperties =
            bind(
                "inventory.reservation.max-ttl.default" to "1h",
                "inventory.reservation.max-ttl.channels.STORE" to "48h",
            )

        assertEquals(Duration.ofHours(48), properties.maxTtl.forChannel("STORE"))
        assertEquals(Duration.ofHours(1), properties.maxTtl.forChannel("OMS"))
    }

    @Test
    fun `아무 설정이 없어도 기본값은 1시간이다`() {
        assertEquals(Duration.ofHours(1), bind().maxTtl.default)
    }
}
