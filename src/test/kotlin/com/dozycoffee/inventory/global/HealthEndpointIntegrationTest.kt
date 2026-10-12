package com.dozycoffee.inventory.global

import com.dozycoffee.auth.test.DozyTestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.LivenessState
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * `local` 프로필 없이 실제 보안 체인과 DB를 거쳐 헬스 체크를 검증한다. 배포 플랫폼의 probe가 토큰 없이 호출하는 경로이고,
 * 그 밖의 actuator 엔드포인트는 노출되지 않아야 한다.
 */
@SpringBootTest
@AutoConfigureWebTestClient
class HealthEndpointIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var environment: Environment

    @AfterEach
    fun restoreReadiness() {
        AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC)
    }

    private fun get(
        path: String,
        token: String? = null,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(path)
            .headers { headers -> token?.let { headers.setBearerAuth(it) } }
            .exchange()

    @Nested
    inner class `토큰 없이 호출` {
        @Test
        fun `전체 헬스는 UP이고 상세 정보는 숨긴다`() {
            get("/actuator/health")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.components")
                .doesNotExist()
        }

        @Test
        fun `liveness와 readiness는 UP이다`() {
            get("/actuator/health/liveness")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
            get("/actuator/health/readiness")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
        }

        @Test
        fun `종료를 시작해 트래픽을 거절하는 상태가 되면 readiness는 503이고 liveness는 그대로 UP이다`() {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC)

            get("/actuator/health/readiness")
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("OUT_OF_SERVICE")
            get("/actuator/health/liveness").expectStatus().isOk
        }

        @Test
        fun `프로세스가 망가진 상태가 되면 liveness는 503이다`() {
            AvailabilityChangeEvent.publish(context, LivenessState.BROKEN)

            try {
                get("/actuator/health/liveness").expectStatus().isEqualTo(503)
            } finally {
                AvailabilityChangeEvent.publish(context, LivenessState.CORRECT)
            }
        }
    }

    @Nested
    inner class `헬스 외 엔드포인트` {
        @Test
        fun `메트릭, 환경, 빈 정보는 노출하지 않는다`() {
            val admin: String = tokens.issue(roles = listOf("inventory:admin"))

            listOf("/actuator/env", "/actuator/metrics", "/actuator/prometheus", "/actuator/beans", "/actuator/info").forEach { path ->
                get(path).expectStatus().isUnauthorized
                get(path, admin).expectStatus().isNotFound
            }
        }

        @Test
        fun `헬스 외의 일반 API는 여전히 인증이 필요하다`() {
            get("/api/v1/inventories").expectStatus().isUnauthorized
        }
    }

    @Nested
    inner class `헬스 그룹 설정` {
        @Test
        fun `readiness는 DB를 포함하고 Kafka는 포함하지 않으며 liveness는 외부 의존성을 보지 않는다`() {
            val readiness: String = checkNotNull(environment.getProperty("management.endpoint.health.group.readiness.include"))
            val liveness: String = checkNotNull(environment.getProperty("management.endpoint.health.group.liveness.include"))

            assertThat(readiness.split(",").map { it.trim() }).containsExactlyInAnyOrder("readinessState", "r2dbc")
            assertThat(liveness.split(",").map { it.trim() }).containsExactly("livenessState")
        }

        @Test
        fun `종료 시 진행 중인 요청을 기다린다`() {
            assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful")
            assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s")
        }
    }
}
