package com.dozycoffee.inventory.reservation.adapter.out.persistence

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateReservationKeyException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
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
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
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

    /** `DatabaseClient`로 직접 쓰는 SQL은 Auditing이 동작하지 않아 `updated_at`, `updated_by`를 SQL에 직접 넣는다 */
    override suspend fun updateState(
        reservation: Reservation,
        expectedStatus: ReservationStatus,
    ): Boolean {
        val reservationId: Long = checkNotNull(reservation.reservationId) { "저장되지 않은 예약의 상태는 갱신할 수 없다" }
        val expiresAt: LocalDateTime? = reservation.expiry.expiresAt
        val confirmedAt: LocalDateTime? = reservation.confirmedAt
        val updated: Long =
            databaseClient
                .sql(UPDATE_STATE)
                .bind("status", reservation.status.name)
                .let { spec ->
                    if (expiresAt ==
                        null
                    ) {
                        spec.bindNull("expiresAt", LocalDateTime::class.java)
                    } else {
                        spec.bind("expiresAt", expiresAt)
                    }
                }.let { spec ->
                    if (confirmedAt ==
                        null
                    ) {
                        spec.bindNull("confirmedAt", LocalDateTime::class.java)
                    } else {
                        spec.bind("confirmedAt", confirmedAt)
                    }
                }.bind("now", LocalDateTime.now(clock))
                .bind("actor", currentActorProvider.get().auditName)
                .bind("reservationId", reservationId)
                .bind("expectedStatus", expectedStatus.name)
                .fetch()
                .awaitRowsUpdated()
        return updated > 0
    }

    override suspend fun updateFulfillment(
        reservation: Reservation,
        expectedStatus: ReservationStatus,
    ): Boolean {
        if (!updateState(reservation, expectedStatus)) return false
        reservation.items.flatMap(ReservationItem::allocations).forEach { allocation: ReservationAllocation ->
            databaseClient
                .sql(UPDATE_FULFILLED_QUANTITY)
                .bind("fulfilledQuantity", allocation.fulfilledQuantity)
                .bind("now", LocalDateTime.now(clock))
                .bind("actor", currentActorProvider.get().auditName)
                .bind("allocationId", checkNotNull(allocation.reservationAllocationId) { "저장되지 않은 할당의 출고 수량은 갱신할 수 없다" })
                .fetch()
                .awaitRowsUpdated()
        }
        return true
    }

    override suspend fun findExpiredIds(
        now: LocalDateTime,
        limit: Int,
    ): List<Long> =
        databaseClient
            .sql(FIND_EXPIRED_IDS)
            .bind("now", now)
            .bind("limit", limit)
            .map { row, _ -> checkNotNull(row.get("reservation_id", Long::class.javaObjectType)) }
            .flow()
            .toList()

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

    private companion object {
        const val UPDATE_STATE: String =
            """
            UPDATE reservation
               SET status = :status, expires_at = :expiresAt, confirmed_at = :confirmedAt, updated_at = :now, updated_by = :actor
             WHERE reservation_id = :reservationId AND status = :expectedStatus
            """

        const val UPDATE_FULFILLED_QUANTITY: String =
            """
            UPDATE reservation_allocation
               SET fulfilled_quantity = :fulfilledQuantity, updated_at = :now, updated_by = :actor
             WHERE reservation_allocation_id = :allocationId
            """

        const val FIND_EXPIRED_IDS: String =
            """
            SELECT reservation_id FROM reservation
             WHERE status = 'RESERVED' AND expires_at <= :now
             ORDER BY expires_at, reservation_id
             LIMIT :limit
            """
    }
}
