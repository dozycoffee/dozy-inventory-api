package com.dozycoffee.inventory.global.domain

import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/** 수량을 바꾸는 요청의 멱등 키. ASCII 문자열이며 대소문자를 구분하고 100자를 넘을 수 없다(ERD-07) */
@JvmInline
value class IdempotencyKey private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 100

        fun of(value: String?): IdempotencyKey {
            if (value.isNullOrBlank() || value.length > MAX_LENGTH || value.any { it.code !in ASCII_PRINTABLE }) {
                throw InvalidDomainValueException(CommonErrorCode.INVALID_IDEMPOTENCY_KEY)
            }
            return IdempotencyKey(value)
        }

        private val ASCII_PRINTABLE: IntRange = 0x21..0x7E
    }
}
