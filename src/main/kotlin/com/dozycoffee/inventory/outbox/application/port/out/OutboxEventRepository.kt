package com.dozycoffee.inventory.outbox.application.port.out

import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import java.time.LocalDateTime

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장하고 조회한다 */
interface OutboxEventRepository {
    suspend fun save(event: OutboxEvent): OutboxEvent

    suspend fun findById(outboxEventId: Long): OutboxEvent?

    /** 발행 대기 이벤트를 `outbox_event_id` 오름차순으로 최대 [limit]개 반환한다 */
    suspend fun findPending(limit: Int): List<OutboxEvent>

    /** 발행 대기 이벤트를 발행 완료로 바꾼다. 이미 발행 완료이면 바꾸지 않고 false를 반환한다 */
    suspend fun markPublished(
        outboxEventId: Long,
        publishedAt: LocalDateTime,
    ): Boolean

    /** 발행에 실패한 이벤트의 시도 횟수를 하나 늘린다 */
    suspend fun increaseAttemptCount(outboxEventId: Long)

    /**
     * 발행 완료 시각이 [cutoff]보다 앞선 발행 완료 이벤트를 `outbox_event_id` 오름차순으로 최대 [limit]개 삭제하고 삭제한 개수를 반환한다.
     * 발행 대기 이벤트는 지우지 않는다.
     */
    suspend fun deletePublishedBefore(
        cutoff: LocalDateTime,
        limit: Int,
    ): Int

    /**
     * 발행기 어드바이저리 락을 기다리지 않고 잡는다. 이미 다른 인스턴스가 잡고 있으면 false를 반환한다.
     * 락은 DB 연결에 묶이므로 [releasePublishLock]까지 같은 트랜잭션(같은 연결) 안에서 호출해야 한다.
     */
    suspend fun tryAcquirePublishLock(): Boolean

    suspend fun releasePublishLock()
}
