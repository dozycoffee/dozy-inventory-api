package com.dozycoffee.inventory.outbox.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.enumeration.OutboxStatus
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
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

@DataR2dbcTest
@ActiveProfiles("local")
@Import(OutboxEventPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class OutboxEventPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: OutboxEventPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

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
