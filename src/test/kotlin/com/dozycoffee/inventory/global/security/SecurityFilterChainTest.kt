package com.dozycoffee.inventory.global.security

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.UUID

@WebFluxTest(SecurityTestController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
class SecurityFilterChainTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    private fun get(
        path: String,
        token: String?,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(path)
            .headers { headers: HttpHeaders -> token?.let { headers.setBearerAuth(it) } }
            .exchange()

    private fun employeeToken(vararg roles: String): String = tokens.issue(roles = roles.toList())

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED와 WWW-Authenticate 헤더를 응답한다`() {
        get("/test/security/any", null)
            .expectStatus()
            .isUnauthorized
            .expectHeader()
            .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("UNAUTHENTICATED")
            .jsonPath("$.traceId")
            .isNotEmpty
    }

    @Test
    fun `믿지 않는 키로 서명한 토큰은 401`() {
        val token: String = tokens.issue(roles = listOf("inventory:admin"), signedBy = DozyTestTokens.Key.UNTRUSTED)

        get("/test/security/any", token).expectStatus().isUnauthorized
    }

    @Test
    fun `다른 audience의 토큰은 401`() {
        val token: String = tokens.issue(roles = listOf("wms:inbound_manager"), audience = listOf("wms"))

        get("/test/security/any", token).expectStatus().isUnauthorized
    }

    @Test
    fun `만료된 토큰은 401`() {
        val token: String =
            tokens.issue(
                roles = listOf("inventory:admin"),
                issuedAt = Instant.now().minusSeconds(7200),
                expiresAt = Instant.now().minusSeconds(3600),
            )

        get("/test/security/any", token).expectStatus().isUnauthorized
    }

    @Test
    fun `inventory role이 하나도 없는 토큰은 403 FORBIDDEN`() {
        get("/test/security/any", employeeToken())
            .expectStatus()
            .isForbidden
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("FORBIDDEN")
    }

    @Test
    fun `다른 audience의 role만 가진 토큰은 role이 없는 것으로 보고 403`() {
        val token: String = tokens.issue(roles = listOf("wms:inbound_manager"), audience = listOf("inventory", "wms"))

        get("/test/security/any", token).expectStatus().isForbidden
    }

    @Test
    fun `세 role은 모두 공통 API를 호출할 수 있다`() {
        listOf("service", "warehouse_manager", "admin").forEach { role: String ->
            get("/test/security/any", employeeToken("inventory:$role")).expectStatus().isOk
        }
    }

    @Test
    fun `admin 전용 API는 admin만 호출할 수 있다`() {
        get("/test/security/admin", employeeToken("inventory:warehouse_manager")).expectStatus().isForbidden
        get("/test/security/admin", employeeToken("inventory:service")).expectStatus().isForbidden
        get("/test/security/admin", employeeToken("inventory:admin")).expectStatus().isOk
    }

    @Test
    fun `토큰의 principalId와 role이 Actor가 된다`() {
        val id: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
        val token: String = tokens.issue(type = PrincipalType.SYSTEM, id = id, roles = listOf("inventory:service"))

        get("/test/security/actor", token)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.auditName")
            .isEqualTo(id.toString())
            .jsonPath("$.roles[0]")
            .isEqualTo("service")
    }
}
