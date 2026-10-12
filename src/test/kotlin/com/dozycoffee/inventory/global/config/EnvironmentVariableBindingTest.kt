package com.dozycoffee.inventory.global.config

import com.dozycoffee.inventory.adjustment.adapter.out.config.AdjustmentProperties
import com.dozycoffee.inventory.reservation.adapter.out.config.ReservationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

/**
 * `docs/configuration.md`에 적은 환경변수 이름이 실제로 설정 속성에 연결되는지 확인한다. 운영 환경에서는 설정 파일을 고치지 않고
 * 환경변수로 값을 바꾸므로 이름 규칙이 어긋나면 조용히 기본값이 쓰인다.
 */
class EnvironmentVariableBindingTest {
    private fun environment(vararg variables: Pair<String, String>): StandardEnvironment {
        val environment = StandardEnvironment()
        environment.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf(*variables)),
        )
        return environment
    }

    @Test
    fun `@Value로 읽는 속성은 점과 하이픈을 밑줄로 바꾼 대문자 이름으로 덮어쓴다`() {
        val environment =
            environment(
                "INVENTORY_OUTBOX_PUBLISHER_INTERVAL" to "PT5S",
                "INVENTORY_OUTBOX_PUBLISHER_BATCH_SIZE" to "300",
                "INVENTORY_OUTBOX_CLEANUP_RETENTION" to "P30D",
                "INVENTORY_RECONCILIATION_CRON" to "0 0 3 * * *",
                "INVENTORY_RECONCILIATION_WMS_BASE_URL" to "http://wms",
                "INVENTORY_EXPIRING_SOON_DAYS" to "14",
                "SERVER_SHUTDOWN" to "immediate",
            )

        assertThat(environment.getProperty("inventory.outbox.publisher.interval")).isEqualTo("PT5S")
        assertThat(environment.getProperty("inventory.outbox.publisher.batch-size")).isEqualTo("300")
        assertThat(environment.getProperty("inventory.outbox.cleanup.retention")).isEqualTo("P30D")
        assertThat(environment.getProperty("inventory.reconciliation.cron")).isEqualTo("0 0 3 * * *")
        assertThat(environment.getProperty("inventory.reconciliation.wms.base-url")).isEqualTo("http://wms")
        assertThat(environment.getProperty("inventory.expiring-soon-days")).isEqualTo("14")
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("immediate")
    }

    @Test
    fun `설정 클래스로 묶인 속성은 값은 같은 규칙으로, 맵 항목은 하이픈을 뺀 이름으로 덮어쓴다`() {
        val environment =
            environment(
                "INVENTORY_ADJUSTMENT_APPROVAL_THRESHOLD_DEFAULT" to "50",
                "INVENTORY_ADJUSTMENT_APPROVALTHRESHOLD_CATEGORIES_BEAN" to "20",
                "INVENTORY_RESERVATION_MAX_TTL_DEFAULT" to "30m",
                "INVENTORY_RESERVATION_MAXTTL_CHANNELS_OMS" to "2h",
            )
        val binder: Binder = Binder(ConfigurationPropertySources.get(environment), null)

        val adjustment: AdjustmentProperties = binder.bind("inventory.adjustment", AdjustmentProperties::class.java).get()
        val reservation: ReservationProperties = binder.bind("inventory.reservation", ReservationProperties::class.java).get()

        assertThat(adjustment.approvalThreshold.default).isEqualTo(50)
        assertThat(adjustment.approvalThreshold.forCategory("BEAN")).isEqualTo(20)
        assertThat(adjustment.approvalThreshold.forCategory("PACKAGING")).isEqualTo(50)
        assertThat(reservation.maxTtl.default).isEqualTo(Duration.ofMinutes(30))
        assertThat(reservation.maxTtl.forChannel("OMS")).isEqualTo(Duration.ofHours(2))
    }
}
