package com.dozycoffee.inventory.global.error

import com.dozycoffee.inventory.global.security.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.charset.StandardCharsets

@WebFluxTest(ErrorTestController::class)
@ActiveProfiles("local")
@Import(SecurityConfig::class)
class GlobalExceptionHandlerTest {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `비즈니스 예외는 ErrorCode의 코드와 상태로 Problem Details를 응답한다`() {
        webTestClient
            .get()
            .uri("/test/errors/not-found")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type")
            .isEqualTo("https://docs.dozycoffee.com/errors/ims-sample-not-found")
            .jsonPath("$.title")
            .isEqualTo("Ims sample not found")
            .jsonPath("$.status")
            .isEqualTo(404)
            .jsonPath("$.detail")
            .isEqualTo("샘플을 찾을 수 없습니다.")
            .jsonPath("$.instance")
            .isEqualTo("/test/errors/not-found")
            .jsonPath("$.code")
            .isEqualTo("IMS_SAMPLE_NOT_FOUND")
    }

    @Test
    fun `ErrorType은 HTTP 상태로 매핑된다`() {
        webTestClient
            .get()
            .uri("/test/errors/conflict")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
        webTestClient
            .get()
            .uri("/test/errors/forbidden")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `에러 응답의 traceId는 X-Trace-Id 헤더와 같다`() {
        val response: WebTestClient.BodyContentSpec =
            webTestClient
                .get()
                .uri("/test/errors/not-found")
                .exchange()
                .expectHeader()
                .exists("X-Trace-Id")
                .expectBody()
        val traceId: String =
            response
                .returnResult()
                .responseHeaders
                .getFirst("X-Trace-Id")
                .orEmpty()

        response.jsonPath("$.traceId").isEqualTo(traceId)
    }

    @Test
    fun `형식이 맞는 요청의 X-Trace-Id는 그대로 쓴다`() {
        webTestClient
            .get()
            .uri("/test/errors/not-found")
            .header("X-Trace-Id", "abc-123")
            .exchange()
            .expectHeader()
            .valueEquals("X-Trace-Id", "abc-123")
            .expectBody()
            .jsonPath("$.traceId")
            .isEqualTo("abc-123")
    }

    @Test
    fun `형식이 맞지 않는 요청의 X-Trace-Id는 새 값으로 대체한다`() {
        val invalid: String = "x".repeat(65)

        val traceId: String =
            webTestClient
                .get()
                .uri("/test/errors/not-found")
                .header("X-Trace-Id", invalid)
                .exchange()
                .returnResult(String::class.java)
                .responseHeaders
                .getFirst("X-Trace-Id")
                .orEmpty()

        assert(traceId.isNotEmpty() && traceId != invalid)
        assert(Regex("[A-Za-z0-9-]{1,64}").matches(traceId))
    }

    @Test
    fun `성공 응답에도 X-Trace-Id 헤더가 붙는다`() {
        webTestClient
            .post()
            .uri("/test/errors/validated")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"a","quantity":1}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .exists("X-Trace-Id")
    }

    @Test
    fun `검증 실패는 VALIDATION_FAILED와 필드별 errors를 응답한다`() {
        webTestClient
            .post()
            .uri("/test/errors/validated")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"","quantity":0}""")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("VALIDATION_FAILED")
            .jsonPath("$.detail")
            .isEqualTo("요청 값이 올바르지 않습니다.")
            .jsonPath("$.errors.length()")
            .isEqualTo(2)
            .jsonPath("$.errors[?(@.field == 'name')].code")
            .isEqualTo("NotBlank")
            .jsonPath("$.errors[?(@.field == 'quantity')].code")
            .isEqualTo("Min")
    }

    @Test
    fun `깨진 JSON은 500이 아니라 400 VALIDATION_FAILED로 응답한다`() {
        webTestClient
            .post()
            .uri("/test/errors/validated")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{not-json")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("VALIDATION_FAILED")
    }

    @Test
    fun `없는 경로는 404 NOT_FOUND로 응답한다`() {
        webTestClient
            .get()
            .uri("/test/errors/does-not-exist")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("NOT_FOUND")
    }

    @Test
    fun `허용하지 않는 메서드는 405 METHOD_NOT_ALLOWED로 응답한다`() {
        webTestClient
            .post()
            .uri("/test/errors/not-found")
            .exchange()
            .expectStatus()
            .isEqualTo(405)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("METHOD_NOT_ALLOWED")
    }

    @Test
    fun `지원하지 않는 본문 형식은 415 UNSUPPORTED_MEDIA_TYPE으로 응답한다`() {
        webTestClient
            .post()
            .uri("/test/errors/validated")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue("plain")
            .exchange()
            .expectStatus()
            .isEqualTo(415)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("UNSUPPORTED_MEDIA_TYPE")
    }

    @Test
    fun `처리하지 못한 예외는 내부 정보 없이 500 INTERNAL_ERROR로 응답한다`() {
        val body: String =
            webTestClient
                .get()
                .uri("/test/errors/unexpected")
                .exchange()
                .expectStatus()
                .is5xxServerError
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.detail")
                .isEqualTo("서버 내부 오류가 발생했습니다.")
                .returnResult()
                .responseBody
                ?.toString(StandardCharsets.UTF_8)
                .orEmpty()

        assert(!body.contains("hunter2") && !body.contains("secret-host") && !body.contains("IllegalStateException"))
    }
}
