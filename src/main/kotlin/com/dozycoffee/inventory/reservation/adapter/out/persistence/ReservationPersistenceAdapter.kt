package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateReservationKeyException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 예약, 항목, 할당은 테이블이 셋이고 R2DBC에는 cascade가 없어 저장 순서를 직접 처리한다(예약 → 항목 → 할당).
 * 새 예약만 저장할 수 있으며 같은 멱등 키가 이미 있으면 [DuplicateReservationKeyException]이다.
 */
@Component
class ReservationPersistenceAdapter(
    private val reservationR2dbcRepository: ReservationR2dbcRepository,
    private val reservationItemR2dbcRepository: ReservationItemR2dbcRepository,
    private val reservationAllocationR2dbcRepository: ReservationAllocationR2dbcRepository,
) : ReservationRepository {
    override suspend fun save(reservation: Reservation): Reservation {
        check(reservation.reservationId == null) { "예약은 새로 저장만 할 수 있다" }
        val saved: ReservationEntity =
            translatingDuplicateKey(duplicate = { DuplicateReservationKeyException() }) {
                reservationR2dbcRepository.save(ReservationEntity.from(reservation))
            }
        val reservationId: Long = checkNotNull(saved.reservationId) { "저장된 예약은 식별자가 있어야 한다" }

        val items: List<ReservationItem> =
            reservation.items.map { item: ReservationItem ->
                val savedItem: ReservationItemEntity = reservationItemR2dbcRepository.save(ReservationItemEntity.from(reservationId, item))
                val itemId: Long = checkNotNull(savedItem.reservationItemId) { "저장된 예약 항목은 식별자가 있어야 한다" }
                val allocations: List<ReservationAllocation> =
                    item.allocations
                        .sortedBy(ReservationAllocation::inventoryId)
                        .map { reservationAllocationR2dbcRepository.save(ReservationAllocationEntity.from(itemId, it)).toDomain() }
                savedItem.toDomain(allocations)
            }
        return saved.toDomain(items)
    }

    override suspend fun findById(reservationId: Long): Reservation? = reservationR2dbcRepository.findById(reservationId)?.let { load(it) }

    override suspend fun existsActive(
        channel: ReservationChannel,
        externalOrderId: ExternalOrderId,
        now: LocalDateTime,
    ): Boolean = reservationR2dbcRepository.countActive(channel.value, externalOrderId.value, now) > 0

    override suspend fun findByIdempotencyKey(idempotencyKey: IdempotencyKey): Reservation? =
        reservationR2dbcRepository.findByIdempotencyKey(idempotencyKey.value)?.let { load(it) }

    private suspend fun load(entity: ReservationEntity): Reservation {
        val reservationId: Long = checkNotNull(entity.reservationId) { "저장된 예약은 식별자가 있어야 한다" }
        val itemEntities: List<ReservationItemEntity> =
            reservationItemR2dbcRepository.findAllByReservationIdOrderByReservationItemIdAsc(reservationId).toList()
        val allocations: Map<Long, List<ReservationAllocation>> =
            if (itemEntities.isEmpty()) {
                emptyMap()
            } else {
                reservationAllocationR2dbcRepository
                    .findAllByReservationItemIdInOrderByInventoryIdAsc(itemEntities.mapNotNull { it.reservationItemId })
                    .toList()
                    .groupBy({ it.reservationItemId }, { it.toDomain() })
            }
        val items: List<ReservationItem> =
            itemEntities.map { item: ReservationItemEntity -> item.toDomain(allocations[item.reservationItemId].orEmpty()) }
        return entity.toDomain(items)
    }
}
