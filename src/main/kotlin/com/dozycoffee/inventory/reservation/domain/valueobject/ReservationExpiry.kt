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
    /** 만료 시각이 있고 [now] 이하이면 만료된 것이다 */
    fun isExpiredAt(now: LocalDateTime): Boolean = expiresAt != null && !expiresAt.isAfter(now)

    /** 만료 시각을 없앤다(확정, 해제). 최대 만료 시각은 그대로 둔다 */
    fun cleared(): ReservationExpiry = ReservationExpiry(null, maxExpiresAt)

    /**
     * 만료 시각을 [newExpiresAt]으로 늘린다. [now] 이후이고 현재 만료 시각 이상이며 최대 만료 시각 이하여야 한다.
     * 같은 시각이면 바뀌지 않는다. 만료 시각이 없는 예약은 늘릴 수 없다.
     */
    fun extendedTo(
        newExpiresAt: LocalDateTime,
        now: LocalDateTime,
    ): ReservationExpiry {
        val current: LocalDateTime = checkNotNull(expiresAt) { "만료 시각이 없는 예약은 연장할 수 없다" }
        if (!newExpiresAt.isAfter(now) || newExpiresAt.isBefore(current) || newExpiresAt.isAfter(maxExpiresAt)) {
            throw InvalidDomainValueException(ReservationErrorCode.INVALID_RESERVATION_EXPIRY)
        }
        return ReservationExpiry(newExpiresAt, maxExpiresAt)
    }

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
