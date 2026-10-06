package com.dozycoffee.ims.global.error

object DomainValidator {
    fun <T : Any> requireNonNull(
        value: T?,
        errorCode: ErrorCode,
    ): T = value ?: throw InvalidDomainValueException(errorCode)
}
