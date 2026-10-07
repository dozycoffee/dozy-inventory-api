package com.dozycoffee.inventory.global.error

object DomainValidator {
    fun <T : Any> requireNonNull(
        value: T?,
        errorCode: ErrorCode,
    ): T = value ?: throw InvalidDomainValueException(errorCode)

    fun requireNotBlank(
        value: String?,
        errorCode: ErrorCode,
    ): String = if (value.isNullOrBlank()) throw InvalidDomainValueException(errorCode) else value
}
