package com.dozycoffee.inventory.global

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

/** 상세 정보를 켠 컨텍스트로 헬스 그룹의 구성 요소를 확인한다. 운영 설정은 상세 정보를 숨기므로 별도 컨텍스트에서만 본다 */
@SpringBootTest(properties = ["management.endpoint.health.show-details=always"])
@AutoConfigureWebTestClient
class HealthGroupComponentsIntegrationTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `readiness에는 DB(r2dbc)가 들어 있고 Kafka는 없다`() {
        webTestClient
            .get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.components.readinessState.status")
            .isEqualTo("UP")
            .jsonPath("$.components.r2dbc.status")
            .isEqualTo("UP")
            .jsonPath("$.components.kafka")
            .doesNotExist()
    }

    @Test
    fun `liveness에는 프로세스 상태만 들어 있다`() {
        webTestClient
            .get()
            .uri("/actuator/health/liveness")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.components.livenessState.status")
            .isEqualTo("UP")
            .jsonPath("$.components.r2dbc")
            .doesNotExist()
    }
}
