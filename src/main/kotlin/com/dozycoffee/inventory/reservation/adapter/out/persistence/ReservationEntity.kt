package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("reservation")
class ReservationEntity(
    @Id
    @Column("reservation_id")
    var reservationId: Long? = null,
    var warehouseId: Long,
    var channel: String,
    var externalOrderId: String,
    var status: ReservationStatus,
    var expiresAt: LocalDateTime?,
    var maxExpiresAt: LocalDateTime,
    var confirmedAt: LocalDateTime?,
    var idempotencyKey: String,
    var requesterService: String,
) : BaseEntity() {

    fun toDomain(items: List<ReservationItem>): Reservation =
        Reservation.reconstitute(
            reservationId = checkNotNull(reservationId) { "저장된 예약은 식별자가 있어야 한다" },
            warehouseId = warehouseId,
            channel = ReservationChannel.of(channel),
            externalOrderId = ExternalOrderId.of(externalOrderId),
            status = status,
            expiry = ReservationExpiry.reconstitute(expiresAt, maxExpiresAt),
            confirmedAt = confirmedAt,
            idempotencyKey = IdempotencyKey.of(idempotencyKey),
            requesterService = RequesterService.of(requesterService),
            items = items,
        )

    companion object {
        fun from(reservation: Reservation): ReservationEntity =
            ReservationEntity(
                reservationId = reservation.reservationId,
                warehouseId = reservation.warehouseId,
                channel = reservation.channel.value,
                externalOrderId = reservation.externalOrderId.value,
                status = reservation.status,
                expiresAt = reservation.expiry.expiresAt,
                maxExpiresAt = reservation.expiry.maxExpiresAt,
                confirmedAt = reservation.confirmedAt,
                idempotencyKey = reservation.idempotencyKey.value,
                requesterService = reservation.requesterService.value,
            )
    }
}
