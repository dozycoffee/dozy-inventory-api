package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class OutboxEventPersistenceAdapter(
    private val outboxEventR2dbcRepository: OutboxEventR2dbcRepository,
    private val databaseClient: DatabaseClient,
) : OutboxEventRepository {
    override suspend fun save(event: OutboxEvent): OutboxEvent {
        check(event.outboxEventId == null) { "이벤트는 새로 저장만 할 수 있다" }
        return outboxEventR2dbcRepository.save(OutboxEventEntity.from(event)).toDomain()
    }

    override suspend fun findById(outboxEventId: Long): OutboxEvent? = outboxEventR2dbcRepository.findById(outboxEventId)?.toDomain()

    override suspend fun findPending(limit: Int): List<OutboxEvent> =
        outboxEventR2dbcRepository.findPending(limit).toList().map { it.toDomain() }

    /** `DatabaseClient`로 직접 쓰는 SQL이다. `outbox_event`에는 수정 감사 컬럼이 없어 상태와 발행 시각만 바꾼다 */
    override suspend fun markPublished(
        outboxEventId: Long,
        publishedAt: LocalDateTime,
    ): Boolean =
        databaseClient
            .sql(MARK_PUBLISHED)
            .bind("publishedAt", publishedAt)
            .bind("outboxEventId", outboxEventId)
            .fetch()
            .awaitRowsUpdated() > 0

    override suspend fun increaseAttemptCount(outboxEventId: Long) {
        databaseClient
            .sql(INCREASE_ATTEMPT_COUNT)
            .bind("outboxEventId", outboxEventId)
            .fetch()
            .awaitRowsUpdated()
    }

    /** `GET_LOCK`은 락을 잡으면 1, 이미 다른 연결이 잡고 있으면 0을 반환한다(대기 시간 0) */
    override suspend fun tryAcquirePublishLock(): Boolean = lockResult(ACQUIRE_LOCK) == 1L

    override suspend fun releasePublishLock() {
        lockResult(RELEASE_LOCK)
    }

    private suspend fun lockResult(sql: String): Long? =
        databaseClient
            .sql(sql)
            .bind("lockName", PUBLISH_LOCK_NAME)
            .fetch()
            .one()
            .awaitSingle()
            .values
            .first()
            ?.toString()
            ?.toLong()

    private companion object {
        const val PUBLISH_LOCK_NAME: String = "dozy_inventory.outbox_publisher"

        const val MARK_PUBLISHED: String =
            """
            UPDATE outbox_event SET status = 'PUBLISHED', published_at = :publishedAt
             WHERE outbox_event_id = :outboxEventId AND status = 'PENDING'
            """

        const val INCREASE_ATTEMPT_COUNT: String =
            "UPDATE outbox_event SET attempt_count = attempt_count + 1 WHERE outbox_event_id = :outboxEventId"

        const val ACQUIRE_LOCK: String = "SELECT GET_LOCK(:lockName, 0)"

        const val RELEASE_LOCK: String = "SELECT RELEASE_LOCK(:lockName)"
    }
}
