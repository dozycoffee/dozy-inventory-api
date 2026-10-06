package com.dozycoffee.ims.global.error

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSourceResolvable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 모든 예외를 RFC 9457 Problem Details로 변환한다 (dozy-auth api/conventions.md §4).
 * `BusinessException`은 자신의 `ErrorCode`를 응답의 `code`로 쓰고, 프레임워크가 만든 4xx는 형식만 맞추며,
 * 그 밖의 예외는 내부 정보 없이 500으로 응답한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        e: BusinessException,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        val errorCode: ErrorCode = e.errorCode
        log.warn("Business exception: code={}, traceId={}, message={}", errorCode.code, TraceIdWebFilter.of(exchange), e.message)
        val status: HttpStatus = ErrorTypeHttpStatusMapper.resolve(errorCode.errorType)
        return ResponseEntity.status(status).body(Problems.create(status, errorCode.code, errorCode.message, exchange))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        e: Exception,
        exchange: ServerWebExchange,
    ): ResponseEntity<ProblemDetail> {
        log.error("Unhandled exception traceId={}", TraceIdWebFilter.of(exchange), e)
        val errorCode: CommonErrorCode = CommonErrorCode.INTERNAL_ERROR
        val problem: ProblemDetail = Problems.create(HttpStatus.INTERNAL_SERVER_ERROR, errorCode.code, errorCode.message, exchange)
        return ResponseEntity.internalServerError().body(problem)
    }

    override fun handleWebExchangeBindException(
        ex: WebExchangeBindException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        ex.body.setProperty("errors", ex.bindingResult.fieldErrors.map { fieldError(it.field, it.code, it.defaultMessage) })
        return handleExceptionInternal(ex, null, headers, status, exchange)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        val errors: List<Map<String, String?>> =
            ex.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { error ->
                    fieldError(
                        fieldName(error) ?: result.methodParameter.parameterName.orEmpty(),
                        error.codes?.lastOrNull(),
                        error.defaultMessage,
                    )
                }
            }
        ex.body.setProperty("errors", errors)
        return handleExceptionInternal(ex, null, headers, status, exchange)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders?,
        status: HttpStatusCode,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Any>> {
        val problem: ProblemDetail = (body as? ProblemDetail) ?: (ex as? ErrorResponse)?.body ?: ProblemDetail.forStatus(status)
        val code: String = Problems.codeFor(status)
        when {
            status.is5xxServerError -> {
                log.error("Framework exception traceId={}", TraceIdWebFilter.of(exchange), ex)
                problem.detail = CommonErrorCode.INTERNAL_ERROR.message
            }

            code == CommonErrorCode.VALIDATION_FAILED.code -> {
                problem.detail = CommonErrorCode.VALIDATION_FAILED.message
            }
        }
        val response: ResponseEntity<Any> =
            ResponseEntity
                .status(status)
                .headers(headers)
                .body(Problems.enrich(problem, code, exchange))
        return Mono.just(response)
    }

    private fun fieldError(
        field: String,
        code: String?,
        message: String?,
    ): Map<String, String?> = mapOf("field" to field, "code" to code, "message" to message)

    private fun fieldName(error: MessageSourceResolvable): String? = (error as? FieldError)?.field

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
