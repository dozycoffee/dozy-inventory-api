package com.dozycoffee.inventory.reservation.adapter.`in`.web.request

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.reflect.KClass

/**
 * 시간대 오프셋을 포함한 ISO-8601 일시(예: `2026-10-07T21:30:00+09:00`, `...Z`)만 허용한다.
 * Jackson은 날짜만 있거나 숫자뿐인 문자열도 `OffsetDateTime`으로 너그럽게 읽어 시간대를 임의로 가정하므로, 문자열로 받아 엄격하게 검사한다.
 */
@MustBeDocumented
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [IsoOffsetDateTimeValidator::class])
annotation class IsoOffsetDateTime(
    val message: String = "시간대 오프셋을 포함한 ISO-8601 일시여야 합니다(예: 2026-10-07T21:30:00+09:00)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class IsoOffsetDateTimeValidator : ConstraintValidator<IsoOffsetDateTime, String?> {
    /** null은 `@NotNull`이 맡는다 */
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null) return true
        return try {
            OffsetDateTime.parse(value)
            true
        } catch (e: DateTimeParseException) {
            false
        }
    }
}
