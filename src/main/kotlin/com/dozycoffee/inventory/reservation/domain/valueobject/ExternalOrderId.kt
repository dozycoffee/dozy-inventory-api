package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 호출 채널의 주문 ID. 비어 있지 않고 100자를 넘을 수 없다 */
@JvmInline
value class ExternalOrderId private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 100

        fun of(value: String?): ExternalOrderId {
            val valid: String = DomainValidator.requireNotBlank(value, ReservationErrorCode.INVALID_EXTERNAL_ORDER_ID)
            if (valid.length > MAX_LENGTH) throw InvalidDomainValueException(ReservationErrorCode.INVALID_EXTERNAL_ORDER_ID)
            return ExternalOrderId(valid)
        }
    }
}
