package com.dozycoffee.inventory.access.domain.valueobject

import com.dozycoffee.inventory.access.domain.exception.AccessErrorCode
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import java.util.UUID

/** `dozy-auth`의 직원 principalId. 8-4-4-4-12 형식의 UUID 문자열을 소문자로 저장하고 비교한다(36자) */
@JvmInline
value class PrincipalId private constructor(
    val value: String,
) {
    companion object {
        private val FORMAT: Regex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun of(value: String?): PrincipalId {
            if (value == null || !FORMAT.matches(value)) throw InvalidDomainValueException(AccessErrorCode.INVALID_PRINCIPAL_ID)
            return PrincipalId(value.lowercase())
        }

        fun of(value: UUID): PrincipalId = PrincipalId(value.toString())
    }
}
