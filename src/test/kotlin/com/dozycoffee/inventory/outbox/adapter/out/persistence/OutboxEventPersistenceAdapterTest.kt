package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import com.dozycoffee.inventory.outbox.newEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.LocalDateTime

@DataR2dbcTest
@ActiveProfiles("local")
@Import(OutboxEventPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class OutboxEventPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: OutboxEventPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var transactionManager: ReactiveTransactionManager

    private val transactionalOperator: TransactionalOperator by lazy { TransactionalOperator.create(transactionManager) }

    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            databaseClient
                .sql("DELETE FROM outbox_event")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private fun event(
        type: AggregateType = AggregateType.RESERVATION,
        payload: String = """{"eventType":"RESERVATION_CREATED","items":[{"productId":100,"quantity":10}]}""",
    ): OutboxEvent = OutboxEvent.create(type, 7L, "RESERVATION_CREATED", "10:7", payload, newEventId())

    @Nested
    inner class `저장과 조회` {
        @Test
        fun `발행 대기로 저장하고 JSON payload와 생성 시각을 다시 읽는다`() =
            runBlocking<Unit> {
                val saved: OutboxEvent = adapter.save(event())
                val loaded: OutboxEvent = checkNotNull(adapter.findById(checkNotNull(saved.outboxEventId)))

                assertThat(loaded.outboxEventId).isNotNull()
                assertThat(loaded.aggregateType).isEqualTo(AggregateType.RESERVATION)
                assertThat(loaded.aggregateId).isEqualTo(7L)
                assertThat(loaded.eventType).isEqualTo("RESERVATION_CREATED")
                assertThat(loaded.partitionKey).isEqualTo("10:7")
                assertThat(loaded.status).isEqualTo(OutboxStatus.PENDING)
                assertThat(loaded.attemptCount).isEqualTo(0)
                assertThat(loaded.createdAt).isNotNull()
                assertThat(loaded.publishedAt).isNull()
                assertThat(loaded.payload).contains("RESERVATION_CREATED").contains("\"quantity\"")
                assertThat(saved).isEqualTo(loaded)
            }

        @Test
        fun `이벤트 ID와 버전과 추적 컨텍스트를 저장하고 그대로 읽는다`() =
            runBlocking<Unit> {
                val id: String = newEventId()
                val trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                val saved: OutboxEvent =
                    adapter.save(OutboxEvent.create(AggregateType.PRODUCT, 3L, "PRODUCT_REGISTERED", "3", "{}", id, 2, trace))

                val loaded: OutboxEvent = checkNotNull(adapter.findById(checkNotNull(saved.outboxEventId)))
                val plain: OutboxEvent = checkNotNull(adapter.findById(checkNotNull(adapter.save(event()).outboxEventId)))

                assertThat(loaded.eventId).isEqualTo(id)
                assertThat(loaded.eventVersion).isEqualTo(2)
                assertThat(loaded.traceparent).isEqualTo(trace)
                assertThat(plain.eventVersion).isEqualTo(1)
                assertThat(plain.traceparent).isNull()
            }

        @Test
        fun `같은 이벤트 ID는 두 번 저장할 수 없다`() =
            runBlocking<Unit> {
                val id: String = newEventId()
                adapter.save(OutboxEvent.create(AggregateType.PRODUCT, 3L, "PRODUCT_REGISTERED", "3", "{}", id))

                assertThrows<Exception> { adapter.save(OutboxEvent.create(AggregateType.PRODUCT, 4L, "PRODUCT_REGISTERED", "4", "{}", id)) }
            }

        @Test
        fun `재발행해도 이벤트 ID는 바뀌지 않는다`() =
            runBlocking<Unit> {
                val saved: OutboxEvent = adapter.save(event())
                val id: Long = checkNotNull(saved.outboxEventId)
                val dead: OutboxEvent = checkNotNull(adapter.findById(id))
                dead.recordFailure("boom", 1, LocalDateTime.of(2026, 10, 13, 9, 0))
                adapter.saveFailure(dead)
                val again: OutboxEvent = checkNotNull(adapter.findById(id))
                again.requeue()
                adapter.saveRequeue(again)

                assertThat(checkNotNull(adapter.findById(id)).eventId).isEqualTo(saved.eventId)
            }

        @Test
        fun `없는 이벤트는 null이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findById(999_999L)).isNull()
            }

        @Test
        fun `식별자가 발행 순서대로 늘어난다`() =
            runBlocking<Unit> {
                val first: Long = checkNotNull(adapter.save(event()).outboxEventId)
                val second: Long = checkNotNull(adapter.save(event()).outboxEventId)

                assertThat(second).isGreaterThan(first)
            }

        @Test
        fun `이미 저장된 이벤트는 다시 저장할 수 없다`() =
            runBlocking<Unit> {
                val saved: OutboxEvent = adapter.save(event())

                assertThrows<IllegalStateException> { adapter.save(saved) }
            }

        @Test
        fun `JSON이 아닌 payload는 저장할 수 없다`() =
            runBlocking<Unit> {
                assertThrows<Exception> { adapter.save(event(payload = "not json")) }
            }
    }

    @Nested
    inner class `발행 대기 조회와 상태 변경` {
        @Test
        fun `발행 대기 이벤트만 식별자 오름차순으로 개수를 제한해 반환한다`() =
            runBlocking<Unit> {
                val ids: List<Long> = (1..5).map { checkNotNull(adapter.save(event()).outboxEventId) }
                assertThat(adapter.markPublished(ids[1], LocalDateTime.of(2026, 10, 8, 9, 0))).isTrue()

                assertThat(adapter.findPending(10).map { it.outboxEventId }).containsExactly(ids[0], ids[2], ids[3], ids[4])
                assertThat(adapter.findPending(2).map { it.outboxEventId }).containsExactly(ids[0], ids[2])
            }

        @Test
        fun `발행 완료로 바꾸면 상태와 발행 시각이 저장되고 다시 바꾸면 false다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(event()).outboxEventId)
                val at: LocalDateTime = LocalDateTime.of(2026, 10, 8, 9, 0, 0, 123_456_000)

                assertThat(adapter.markPublished(id, at)).isTrue()
                assertThat(adapter.markPublished(id, at.plusMinutes(1))).isFalse()

                val loaded: OutboxEvent = checkNotNull(adapter.findById(id))
                assertThat(loaded.status).isEqualTo(OutboxStatus.PUBLISHED)
                assertThat(loaded.publishedAt).isEqualTo(at)
                assertThat(adapter.findPending(10)).isEmpty()
            }

        @Test
        fun `실패를 저장하면 시도 횟수와 마지막 오류가 남고 임계치 전에는 발행 대기다`() =
            runBlocking<Unit> {
                val saved: OutboxEvent = adapter.save(event())
                val id: Long = checkNotNull(saved.outboxEventId)
                val loaded: OutboxEvent = checkNotNull(adapter.findById(id))
                loaded.recordFailure("RecordTooLargeException: too big", 3, LocalDateTime.of(2026, 10, 12, 9, 0))

                assertThat(adapter.saveFailure(loaded)).isTrue()

                val after: OutboxEvent = checkNotNull(adapter.findById(id))
                assertThat(after.attemptCount).isEqualTo(1)
                assertThat(after.lastError).isEqualTo("RecordTooLargeException: too big")
                assertThat(after.status).isEqualTo(OutboxStatus.PENDING)
                assertThat(adapter.findPending(10).map { it.outboxEventId }).containsExactly(id)
            }

        @Test
        fun `격리를 저장하면 발행기가 가져가지 않고 격리 시각이 남으며 한 번 더 저장하면 false다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(event()).outboxEventId)
                val at: LocalDateTime = LocalDateTime.of(2026, 10, 12, 9, 0, 0, 123_456_000)
                val loaded: OutboxEvent = checkNotNull(adapter.findById(id))
                loaded.recordFailure("boom", 1, at)

                assertThat(adapter.saveFailure(loaded)).isTrue()
                assertThat(adapter.saveFailure(loaded)).isFalse()

                val after: OutboxEvent = checkNotNull(adapter.findById(id))
                assertThat(after.status).isEqualTo(OutboxStatus.DEAD)
                assertThat(after.deadAt).isEqualTo(at)
                assertThat(adapter.findPending(10)).isEmpty()
            }

        @Test
        fun `이미 발행된 이벤트의 실패 저장은 아무것도 바꾸지 않고 false다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(event()).outboxEventId)
                val stale: OutboxEvent = checkNotNull(adapter.findById(id))
                adapter.markPublished(id, LocalDateTime.of(2026, 10, 12, 9, 0))
                stale.recordFailure("boom", 1, LocalDateTime.of(2026, 10, 12, 9, 0))

                assertThat(adapter.saveFailure(stale)).isFalse()

                assertThat(checkNotNull(adapter.findById(id)).status).isEqualTo(OutboxStatus.PUBLISHED)
            }

        @Test
        fun `재발행을 저장하면 발행 대기로 돌아가고 시도 횟수와 오류와 격리 시각이 지워진다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(event()).outboxEventId)
                val dead: OutboxEvent = checkNotNull(adapter.findById(id))
                dead.recordFailure("boom", 1, LocalDateTime.of(2026, 10, 12, 9, 0))
                adapter.saveFailure(dead)
                val again: OutboxEvent = checkNotNull(adapter.findById(id))
                again.requeue()

                assertThat(adapter.saveRequeue(again)).isTrue()
                assertThat(adapter.saveRequeue(again)).isFalse()

                val after: OutboxEvent = checkNotNull(adapter.findById(id))
                assertThat(after.status).isEqualTo(OutboxStatus.PENDING)
                assertThat(after.attemptCount).isEqualTo(0)
                assertThat(after.lastError).isNull()
                assertThat(after.deadAt).isNull()
                assertThat(adapter.findPending(10).map { it.outboxEventId }).containsExactly(id)
            }

        @Test
        fun `상태별 목록은 최신순이고 페이지로 자르며 개수를 센다`() =
            runBlocking<Unit> {
                val ids: List<Long> = (1..5).map { checkNotNull(adapter.save(event()).outboxEventId) }
                listOf(ids[0], ids[2], ids[4]).forEach {
                    val dead: OutboxEvent = checkNotNull(adapter.findById(it))
                    dead.recordFailure("boom", 1, LocalDateTime.of(2026, 10, 12, 9, 0))
                    adapter.saveFailure(dead)
                }

                assertThat(adapter.findByStatus(OutboxStatus.DEAD, 0, 10).map { it.outboxEventId }).containsExactly(ids[4], ids[2], ids[0])
                assertThat(adapter.findByStatus(OutboxStatus.DEAD, 1, 1).map { it.outboxEventId }).containsExactly(ids[2])
                assertThat(adapter.findByStatus(OutboxStatus.PENDING, 0, 10).map { it.outboxEventId }).containsExactly(ids[3], ids[1])
                assertThat(adapter.countByStatus(OutboxStatus.DEAD)).isEqualTo(3L)
                assertThat(adapter.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(0L)
            }

        @Test
        fun `발행 완료된 이벤트가 없는 상태에서는 빈 목록이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findPending(10)).isEmpty()
            }
    }

    @Nested
    inner class `발행 완료 이벤트 삭제` {
        private val base: LocalDateTime = LocalDateTime.of(2026, 10, 1, 12, 0)

        private suspend fun savePublished(publishedAt: LocalDateTime): Long {
            val id: Long = checkNotNull(adapter.save(event()).outboxEventId)
            adapter.markPublished(id, publishedAt)
            return id
        }

        private suspend fun remainingIds(): List<Long> =
            databaseClient
                .sql("SELECT outbox_event_id FROM outbox_event ORDER BY outbox_event_id")
                .fetch()
                .all()
                .collectList()
                .awaitSingle()
                .map { it["outbox_event_id"].toString().toLong() }

        @Test
        fun `기준 시각보다 앞서 발행된 이벤트만 지우고 기준 시각과 같거나 뒤인 이벤트는 남긴다`() =
            runBlocking<Unit> {
                val old: Long = savePublished(base.minusSeconds(1))
                val same: Long = savePublished(base)
                val recent: Long = savePublished(base.plusSeconds(1))

                assertThat(adapter.deletePublishedBefore(base, 100)).isEqualTo(1)

                assertThat(remainingIds()).containsExactly(same, recent)
                assertThat(adapter.findById(old)).isNull()
            }

        @Test
        fun `발행 대기 이벤트는 오래되어도 지우지 않는다`() =
            runBlocking<Unit> {
                val pending: Long = checkNotNull(adapter.save(event()).outboxEventId)
                savePublished(base.minusDays(30))

                assertThat(adapter.deletePublishedBefore(base, 100)).isEqualTo(1)

                assertThat(remainingIds()).containsExactly(pending)
            }

        @Test
        fun `한 번에 지울 개수를 넘으면 식별자가 작은 이벤트부터 지우고 나머지는 다음 호출에서 지운다`() =
            runBlocking<Unit> {
                val ids: List<Long> = (1..5).map { savePublished(base.minusDays(1)) }

                assertThat(adapter.deletePublishedBefore(base, 2)).isEqualTo(2)
                assertThat(remainingIds()).containsExactlyElementsOf(ids.drop(2))
                assertThat(adapter.deletePublishedBefore(base, 10)).isEqualTo(3)
                assertThat(remainingIds()).isEmpty()
                assertThat(adapter.deletePublishedBefore(base, 10)).isEqualTo(0)
            }
    }

    @Nested
    inner class `발행기 락` {
        @Test
        fun `락을 잡은 동안 다른 연결은 잡지 못하고 놓으면 다시 잡을 수 있다`() =
            runBlocking<Unit> {
                val holderHasLock = CompletableDeferred<Unit>()
                val otherFinished = CompletableDeferred<Unit>()

                val holder =
                    async(Dispatchers.Default) {
                        transactionalOperator.executeAndAwait {
                            assertThat(adapter.tryAcquirePublishLock()).isTrue()
                            holderHasLock.complete(Unit)
                            otherFinished.await()
                            adapter.releasePublishLock()
                        }
                    }
                holderHasLock.await()

                val other: Boolean? = transactionalOperator.executeAndAwait { adapter.tryAcquirePublishLock() }
                otherFinished.complete(Unit)
                holder.await()

                assertThat(other).isFalse()
                assertThat(
                    transactionalOperator.executeAndAwait { adapter.tryAcquirePublishLock().also { adapter.releasePublishLock() } },
                ).isTrue()
            }

        @Test
        fun `같은 연결에서 다시 잡아도 성공하고 놓은 뒤에는 다른 연결이 잡을 수 있다`() =
            runBlocking<Unit> {
                val acquired: Boolean? =
                    transactionalOperator.executeAndAwait {
                        val first: Boolean = adapter.tryAcquirePublishLock()
                        val again: Boolean = adapter.tryAcquirePublishLock()
                        adapter.releasePublishLock()
                        adapter.releasePublishLock()
                        first && again
                    }

                assertThat(acquired).isTrue()
                assertThat(
                    transactionalOperator.executeAndAwait { adapter.tryAcquirePublishLock().also { adapter.releasePublishLock() } },
                ).isTrue()
            }
    }

    @Nested
    inner class `스키마` {
        private suspend fun checkValues(constraint: String): List<String> {
            val clause: String =
                databaseClient
                    .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = :name")
                    .bind("name", constraint)
                    .fetch()
                    .one()
                    .awaitFirst()["CHECK_CLAUSE"] as String
            return Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()
        }

        @Test
        fun `AggregateType과 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_outbox_aggregate_type"),
                ).containsExactlyInAnyOrderElementsOf(AggregateType.entries.map { it.name })
            }

        @Test
        fun `OutboxStatus와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(checkValues("ck_outbox_status")).containsExactlyInAnyOrderElementsOf(OutboxStatus.entries.map { it.name })
            }
    }
}
