package com.dozycoffee.inventory.global.error

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.web.server.ServerWebExchange
import java.net.URI

/** 에러 응답 본문. RFC 9457 Problem Details에 `code`와 `traceId`를 더한 dozy-auth 규약(api/conventions.md §4)을 따른다 */
internal object Problems {
    private const val TYPE_BASE: String = "https://docs.dozycoffee.com/errors/"

    fun create(
        status: HttpStatusCode,
        code: String,
        detail: String?,
        exchange: ServerWebExchange,
    ): ProblemDetail = enrich(ProblemDetail.forStatusAndDetail(status, detail), code, exchange)

    /** 프레임워크가 만든 [problem]에도 `type`, `title`, `instance`, `code`, `traceId`를 채운다 */
    fun enrich(
        problem: ProblemDetail,
        code: String,
        exchange: ServerWebExchange,
    ): ProblemDetail {
        problem.type = URI.create(TYPE_BASE + code.lowercase().replace('_', '-'))
        problem.title = code.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        runCatching { URI(exchange.request.path.value()) }.onSuccess { problem.instance = it }
        problem.setProperty("code", code)
        TraceIdWebFilter.of(exchange)?.let { problem.setProperty("traceId", it) }
        return problem
    }

    /** 프레임워크가 만든 에러(400, 404, 405 등)에 쓸 code. dozy-auth 에러 코드 표의 이름과 맞춘다 */
    fun codeFor(status: HttpStatusCode): String =
        when {
            status.value() == HttpStatus.BAD_REQUEST.value() -> CommonErrorCode.VALIDATION_FAILED.code
            status.value() == HttpStatus.NOT_FOUND.value() -> "NOT_FOUND"
            status.is5xxServerError -> CommonErrorCode.INTERNAL_ERROR.code
            else -> HttpStatus.resolve(status.value())?.name ?: CommonErrorCode.VALIDATION_FAILED.code
        }
}
