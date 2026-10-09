package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
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
    ): OutboxEvent = OutboxEvent.create(type, 7L, "RESERVATION_CREATED", "10:7", payload)

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
        fun `시도 횟수를 하나씩 늘리고 상태는 그대로다`() =
            runBlocking<Unit> {
                val id: Long = checkNotNull(adapter.save(event()).outboxEventId)

                adapter.increaseAttemptCount(id)
                adapter.increaseAttemptCount(id)

                val loaded: OutboxEvent = checkNotNull(adapter.findById(id))
                assertThat(loaded.attemptCount).isEqualTo(2)
                assertThat(loaded.status).isEqualTo(OutboxStatus.PENDING)
                assertThat(adapter.findPending(10).map { it.outboxEventId }).containsExactly(id)
            }

        @Test
        fun `발행 완료된 이벤트가 없는 상태에서는 빈 목록이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findPending(10)).isEmpty()
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
