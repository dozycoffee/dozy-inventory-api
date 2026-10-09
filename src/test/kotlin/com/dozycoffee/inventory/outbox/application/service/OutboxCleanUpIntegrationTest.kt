package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.outbox.application.port.`in`.CleanUpOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/** 실제 MySQL로 보관 기간이 지난 발행 완료 이벤트만 지워지고 여러 인스턴스가 동시에 지워도 안전한지 검증한다 */
@InventoryIntegrationTest
class OutboxCleanUpIntegrationTest {
    @Autowired
    private lateinit var cleanUpOutboxEventsUseCase: CleanUpOutboxEventsUseCase

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var clock: Clock

    private lateinit var fixture: InventoryDbFixture

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun saved(publishedAt: LocalDateTime?): Long {
        val id: Long =
            checkNotNull(
                outboxEventRepository.save(OutboxEvent.create(AggregateType.PRODUCT, 1L, "PRODUCT_REGISTERED", "1", "{}")).outboxEventId,
            )
        if (publishedAt != null) outboxEventRepository.markPublished(id, publishedAt)
        return id
    }

    private suspend fun count(): Long =
        databaseClient
            .sql("SELECT COUNT(*) AS c FROM outbox_event")
            .fetch()
            .one()
            .awaitFirst()["c"]
            .toString()
            .toLong()

    @Test
    fun `보관 기간이 지난 발행 완료 이벤트만 지우고 최근 발행분과 발행 대기분은 남긴다`() =
        runBlocking<Unit> {
            val now: LocalDateTime = LocalDateTime.now(clock)
            saved(now.minusDays(8))
            saved(now.minusDays(30))
            val recent: Long = saved(now.minusDays(1))
            val pending: Long = saved(null)

            assertThat(cleanUpOutboxEventsUseCase.cleanUp(Duration.ofDays(7), 100)).isEqualTo(2)

            assertThat(count()).isEqualTo(2L)
            assertThat(outboxEventRepository.findById(recent)).isNotNull()
            assertThat(outboxEventRepository.findById(pending)).isNotNull()
        }

    @Test
    fun `정리가 여러 개 동시에 돌아도 각 이벤트는 한 번만 지워진다`() =
        runBlocking<Unit> {
            val old: LocalDateTime = LocalDateTime.now(clock).minusDays(10)
            repeat(40) { saved(old) }

            val deleted: List<Int> =
                (1..5)
                    .map {
                        async(
                            Dispatchers.IO,
                        ) { cleanUpOutboxEventsUseCase.cleanUp(Duration.ofDays(7), 7) }
                    }.awaitAll()

            // 겹쳐 지워도 한 행이 두 번 세어지지 않으므로 지운 합계와 남은 행의 합은 처음 개수와 같다
            assertThat(deleted.sum()).isBetween(7, 35)
            assertThat(deleted.sum() + count()).isEqualTo(40L)
            while (cleanUpOutboxEventsUseCase.cleanUp(Duration.ofDays(7), 100) > 0) {
                // 남은 이벤트를 모두 지운다
            }
            assertThat(count()).isEqualTo(0L)
        }
}
