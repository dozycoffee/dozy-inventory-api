package com.dozycoffee.inventory.global.security

import com.dozycoffee.auth.test.DozyTestTokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(SecurityTestController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@TestPropertySource(properties = ["inventory.cors.allowed-origins=http://localhost:5173,https://admin.dozycoffee.com"])
class CorsTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    private fun bearer(): String = tokens.issue(roles = listOf("inventory:admin"))

    @Test
    fun `허용된 origin으로 요청하면 Access-Control-Allow-Origin을 붙여 응답한다`() {
        webTestClient
            .get()
            .uri("http://localhost/test/security/any")
            .header(HttpHeaders.ORIGIN, "https://admin.dozycoffee.com")
            .headers { it.setBearerAuth(bearer()) }
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.dozycoffee.com")
    }

    @Test
    fun `허용된 origin의 인증 실패 응답에도 CORS 헤더가 붙어 브라우저가 401을 읽을 수 있다`() {
        webTestClient
            .get()
            .uri("http://localhost/test/security/any")
            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
    }

    @Test
    fun `허용된 origin의 preflight 요청은 토큰 없이도 200과 허용 헤더를 응답한다`() {
        webTestClient
            .options()
            .uri("http://localhost/test/security/any")
            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
            .expectHeader()
            .value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS) { assert(it.contains("Authorization", ignoreCase = true)) }
    }

    @Test
    fun `허용되지 않은 origin의 요청은 403이다`() {
        webTestClient
            .get()
            .uri("http://localhost/test/security/any")
            .header(HttpHeaders.ORIGIN, "http://evil.example.com")
            .headers { it.setBearerAuth(bearer()) }
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `허용되지 않은 origin의 preflight 요청은 403이다`() {
        webTestClient
            .options()
            .uri("http://localhost/test/security/any")
            .header(HttpHeaders.ORIGIN, "http://evil.example.com")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `origin 헤더가 없는 서버 간 호출은 영향을 받지 않는다`() {
        webTestClient
            .get()
            .uri("http://localhost/test/security/any")
            .headers { it.setBearerAuth(bearer()) }
            .exchange()
            .expectStatus()
            .isOk
    }
}
