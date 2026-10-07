package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("reservation_item")
class ReservationItemEntity(
    @Id
    @Column("reservation_item_id")
    var reservationItemId: Long? = null,
    var reservationId: Long,
    var productId: Long,
    var requestedQuantity: Int,
) : BaseEntity() {
    fun toDomain(allocations: List<ReservationAllocation>): ReservationItem =
        ReservationItem.reconstitute(
            reservationItemId = checkNotNull(reservationItemId) { "저장된 예약 항목은 식별자가 있어야 한다" },
            productId = productId,
            requestedQuantity = requestedQuantity,
            allocations = allocations,
        )

    companion object {
        fun from(
            reservationId: Long,
            item: ReservationItem,
        ): ReservationItemEntity =
            ReservationItemEntity(
                reservationItemId = item.reservationItemId,
                reservationId = reservationId,
                productId = item.productId,
                requestedQuantity = item.requestedQuantity,
            )
    }
}
