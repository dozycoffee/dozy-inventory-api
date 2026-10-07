package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("reservation_allocation")
class ReservationAllocationEntity(
    @Id
    @Column("reservation_allocation_id")
    var reservationAllocationId: Long? = null,
    var reservationItemId: Long,
    var inventoryId: Long,
    var quantity: Int,
    var fulfilledQuantity: Int,
) : BaseEntity() {
    fun toDomain(): ReservationAllocation =
        ReservationAllocation.reconstitute(
            reservationAllocationId = checkNotNull(reservationAllocationId) { "저장된 할당은 식별자가 있어야 한다" },
            inventoryId = inventoryId,
            quantity = quantity,
            fulfilledQuantity = fulfilledQuantity,
        )

    companion object {
        fun from(
            reservationItemId: Long,
            allocation: ReservationAllocation,
        ): ReservationAllocationEntity =
            ReservationAllocationEntity(
                reservationAllocationId = allocation.reservationAllocationId,
                reservationItemId = reservationItemId,
                inventoryId = allocation.inventoryId,
                quantity = allocation.quantity,
                fulfilledQuantity = allocation.fulfilledQuantity,
            )
    }
}
