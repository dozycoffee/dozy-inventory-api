package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.persistence.translatingDuplicateKey
import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateLotException
import com.dozycoffee.inventory.inventory.domain.model.Lot
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@Component
class LotPersistenceAdapter(
    private val lotR2dbcRepository: LotR2dbcRepository,
    private val databaseClient: DatabaseClient,
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) : LotRepository {
    override suspend fun save(lot: Lot): Lot {
        val entity: LotEntity = LotEntity.from(lot)
        lot.lotId?.let { lotId: Long ->
            val existing: LotEntity = checkNotNull(lotR2dbcRepository.findById(lotId)) { "갱신할 Lot이 없다: $lotId" }
            entity.copyAuditFieldsFrom(existing)
        }
        return translatingDuplicateKey(duplicate = { DuplicateLotException() }) {
            lotR2dbcRepository.save(entity).toDomain()
        }
    }

    override suspend fun findById(lotId: Long): Lot? = lotR2dbcRepository.findById(lotId)?.toDomain()

    override suspend fun findByProductIdAndLotNumber(
        productId: Long,
        lotNumber: String,
    ): Lot? = lotR2dbcRepository.findByProductIdAndLotNumber(productId, lotNumber)?.toDomain()

    override suspend fun findScanCandidateIds(
        today: LocalDate,
        soonLimit: LocalDate,
        limit: Int,
    ): List<Long> =
        databaseClient
            .sql(SCAN_CANDIDATES)
            .bind("today", today)
            .bind("soonLimit", soonLimit)
            .bind("limit", limit)
            .map { row, _ -> checkNotNull(row.get("lot_id", Long::class.javaObjectType)) }
            .flow()
            .toList()

    /** `DatabaseClient`로 직접 쓰는 SQL은 Auditing이 동작하지 않아 `updated_at`, `updated_by`를 SQL에 직접 넣는다 */
    override suspend fun updateStatus(
        lot: Lot,
        expectedStatus: LotStatus,
    ): Boolean =
        databaseClient
            .sql(UPDATE_STATUS)
            .bind("status", lot.lotStatus.name)
            .bind("now", LocalDateTime.now(clock))
            .bind("actor", currentActorProvider.get().auditName)
            .bind("lotId", checkNotNull(lot.lotId) { "저장되지 않은 Lot의 상태는 갱신할 수 없다" })
            .bind("expectedStatus", expectedStatus.name)
            .fetch()
            .awaitRowsUpdated() > 0

    private companion object {
        const val SCAN_CANDIDATES: String =
            """
            SELECT l.lot_id
              FROM lot l
             WHERE l.expiration_date IS NOT NULL
               AND ((l.lot_status = 'NORMAL' AND l.expiration_date <= :soonLimit)
                 OR (l.lot_status = 'EXPIRING_SOON' AND l.expiration_date <= :today)
                 OR (l.lot_status = 'EXPIRED' AND EXISTS (SELECT 1 FROM inventory i
                                                          WHERE i.lot_id = l.lot_id AND i.quality_status = 'NORMAL'
                                                            AND i.quantity > i.reserved_quantity)))
             ORDER BY l.lot_id
             LIMIT :limit
            """

        const val UPDATE_STATUS: String =
            """
            UPDATE lot SET lot_status = :status, updated_at = :now, updated_by = :actor
             WHERE lot_id = :lotId AND lot_status = :expectedStatus
            """
    }
}
