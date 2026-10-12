package com.dozycoffee.inventory.outbox.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.exception.InvalidOutboxStateException
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import java.time.LocalDateTime

/**
 * 발행 대기 이벤트. 수량 변경과 같은 트랜잭션으로 저장하고 별도 발행기가 Kafka로 내보낸다(ADR-0025).
 * [payload]는 JSON 문자열이고 같은 [partitionKey]는 [outboxEventId] 순서로 발행한다.
 */
class OutboxEvent private constructor(
    val outboxEventId: Long?,
    val aggregateType: AggregateType,
    val aggregateId: Long,
    val eventType: String,
    val partitionKey: String,
    val payload: String,
    status: OutboxStatus,
    attemptCount: Int,
    lastError: String?,
    val createdAt: LocalDateTime?,
    val publishedAt: LocalDateTime?,
    deadAt: LocalDateTime?,
) {
    var status: OutboxStatus = status
        private set

    /** 발행에 실패한 횟수. 이벤트 고유의 실패만 센다(브로커 장애처럼 공통 문제는 세지 않는다, ADR-0037) */
    var attemptCount: Int = attemptCount
        private set

    var lastError: String? = lastError
        private set

    /** 격리(`DEAD`)된 시각 */
    var deadAt: LocalDateTime? = deadAt
        private set

    /**
     * 이벤트 고유의 발행 실패를 기록한다. 시도 횟수를 하나 늘리고 마지막 오류를 남기며, 횟수가 [maxAttempts]에 이르면 `DEAD`로 격리하고 true를 반환한다.
     * 발행 대기 이벤트만 기록할 수 있다([InvalidOutboxStateException]).
     */
    fun recordFailure(
        error: String?,
        maxAttempts: Int,
        now: LocalDateTime,
    ): Boolean {
        if (status != OutboxStatus.PENDING) throw InvalidOutboxStateException()
        if (maxAttempts < 1) throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
        attemptCount += 1
        lastError = error?.take(MAX_ERROR_LENGTH)
        if (attemptCount < maxAttempts) return false
        status = OutboxStatus.DEAD
        deadAt = now
        return true
    }

    /** 격리된 이벤트를 다시 발행 대기로 돌린다. 시도 횟수와 오류, 격리 시각을 지운다. 격리된 이벤트만 가능하다([InvalidOutboxStateException]) */
    fun requeue() {
        if (status != OutboxStatus.DEAD) throw InvalidOutboxStateException()
        status = OutboxStatus.PENDING
        attemptCount = 0
        lastError = null
        deadAt = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return outboxEventId != null && outboxEventId == other.outboxEventId
    }

    override fun hashCode(): Int = outboxEventId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        const val MAX_KEY_LENGTH: Int = 100
        const val MAX_ERROR_LENGTH: Int = 500

        fun create(
            aggregateType: AggregateType,
            aggregateId: Long,
            eventType: String?,
            partitionKey: String?,
            payload: String?,
        ): OutboxEvent {
            if (aggregateId < 1 ||
                eventType.isNullOrBlank() ||
                eventType.length > MAX_KEY_LENGTH ||
                partitionKey.isNullOrBlank() ||
                partitionKey.length > MAX_KEY_LENGTH ||
                payload.isNullOrBlank()
            ) {
                throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
            }
            return OutboxEvent(
                null,
                aggregateType,
                aggregateId,
                eventType,
                partitionKey,
                payload,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
            )
        }

        fun reconstitute(
            outboxEventId: Long,
            aggregateType: AggregateType,
            aggregateId: Long,
            eventType: String,
            partitionKey: String,
            payload: String,
            status: OutboxStatus,
            attemptCount: Int,
            createdAt: LocalDateTime,
            publishedAt: LocalDateTime?,
            lastError: String? = null,
            deadAt: LocalDateTime? = null,
        ): OutboxEvent =
            OutboxEvent(
                outboxEventId,
                aggregateType,
                aggregateId,
                eventType,
                partitionKey,
                payload,
                status,
                attemptCount,
                lastError,
                createdAt,
                publishedAt,
                deadAt,
            )
    }
}
