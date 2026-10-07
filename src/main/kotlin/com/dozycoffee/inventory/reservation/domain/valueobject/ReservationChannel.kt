package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode

/** 예약을 요청한 호출 채널. 값은 확정되어 있지 않아 형식만 검증한다(비어 있지 않고 50자 이하, ADR-0022) */
@JvmInline
value class ReservationChannel private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH: Int = 50

        fun of(value: String?): ReservationChannel {
            val valid: String = DomainValidator.requireNotBlank(value, ReservationErrorCode.INVALID_RESERVATION_CHANNEL)
            if (valid.length > MAX_LENGTH) throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_CHANNEL)
            return ReservationChannel(valid)
        }
    }
}
