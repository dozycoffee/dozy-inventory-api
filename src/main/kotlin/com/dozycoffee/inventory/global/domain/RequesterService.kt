package com.dozycoffee.inventory.global.domain

import com.dozycoffee.inventory.global.error.CommonErrorCode
import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/** 요청을 보낸 서비스(system client의 principalId 등)의 식별자. 비어 있지 않고 50자를 넘을 수 없다 */
@JvmInline
value class RequesterService private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 50

        fun of(value: String?): RequesterService {
            val valid: String = DomainValidator.requireNotBlank(value, CommonErrorCode.INVALID_REQUESTER_SERVICE)
            if (valid.length > MAX_LENGTH) throw InvalidDomainValueException(CommonErrorCode.INVALID_REQUESTER_SERVICE)
            return RequesterService(valid)
        }
    }
}
