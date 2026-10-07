package com.dozycoffee.inventory.global.error

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

enum class SampleErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    NOT_FOUND(ErrorType.NOT_FOUND, "INV_SAMPLE_NOT_FOUND", "샘플을 찾을 수 없습니다."),
    CONFLICT(ErrorType.CONFLICT, "INV_SAMPLE_CONFLICT", "샘플이 충돌합니다."),
    FORBIDDEN(ErrorType.FORBIDDEN, "INV_SAMPLE_FORBIDDEN", "샘플에 접근할 수 없습니다."),
}

class SampleException(
    errorCode: ErrorCode,
) : DomainException(errorCode)

data class SampleRequest(
    @field:NotBlank val name: String?,
    @field:Min(1) val quantity: Int?,
)

@RestController
@RequestMapping("/test/errors")
class ErrorTestController {
    @GetMapping("/not-found")
    fun notFound(): String = throw SampleException(SampleErrorCode.NOT_FOUND)

    @GetMapping("/conflict")
    fun conflict(): String = throw SampleException(SampleErrorCode.CONFLICT)

    @GetMapping("/forbidden")
    fun forbidden(): String = throw SampleException(SampleErrorCode.FORBIDDEN)

    @GetMapping("/unexpected")
    fun unexpected(): String = throw IllegalStateException("jdbc:mysql://secret-host password=hunter2")

    @PostMapping("/validated")
    fun validated(
        @Valid @RequestBody request: SampleRequest,
    ): String = "ok"
}
