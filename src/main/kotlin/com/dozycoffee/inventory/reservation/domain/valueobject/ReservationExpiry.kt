package com.dozycoffee.inventory.reservation.domain.valueobject

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationErrorCode
import java.time.LocalDateTime

/**
 * 예약의 만료 시각과 그 상한. 확정 전(`RESERVED`) 예약만 [expiresAt]이 있고 확정된 예약은 null이다.
 * 만료 시각은 상한([maxExpiresAt], 생성 시각 + 채널 TTL 상한)을 넘을 수 없다.
 */
data class ReservationExpiry private constructor(
    val expiresAt: LocalDateTime?,
    val maxExpiresAt: LocalDateTime,
) {
    companion object {
        /** 새 예약의 만료. [now] 이후이고 [maxExpiresAt] 이하여야 한다 */
        fun create(
            expiresAt: LocalDateTime,
            maxExpiresAt: LocalDateTime,
            now: LocalDateTime,
        ): ReservationExpiry {
            if (!expiresAt.isAfter(now) || expiresAt.isAfter(maxExpiresAt)) {
                throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_EXPIRY)
            }
            return ReservationExpiry(expiresAt, maxExpiresAt)
        }

        fun reconstitute(
            expiresAt: LocalDateTime?,
            maxExpiresAt: LocalDateTime,
        ): ReservationExpiry = ReservationExpiry(expiresAt, maxExpiresAt)
    }
}
