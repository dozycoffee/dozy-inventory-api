package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
@Import(
    InventoryHistoryPersistenceAdapter::class,
    LotPersistenceAdapter::class,
    R2dbcConfig::class,
    ClockConfig::class,
    LocalActorProvider::class,
)
class InventoryHistoryPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: InventoryHistoryPersistenceAdapter

    @Autowired
    private lateinit var lotAdapter: LotPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private lateinit var fixture: InventoryDbFixture
    private var inventoryId: Long = 0L
    private var otherInventoryId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotAdapter)
            val productId: Long = fixture.seedProduct()
            val lotId: Long = checkNotNull(fixture.seedLot(productId).lotId)
            inventoryId = fixture.insertInventory(10L, productId, lotId)
            otherInventoryId = fixture.insertInventory(11L, productId, lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun history(
        key: String = "svc-wms-inbound-1",
        inventoryId: Long = this.inventoryId,
        type: HistoryType = HistoryType.INBOUND,
        change: Int = 5,
        after: Int = 15,
        reference: ReferenceType = ReferenceType.INBOUND_ITEM,
    ): InventoryHistory =
        InventoryHistory.create(inventoryId, type, change, after, reference, 77L, IdempotencyKey.of(key), RequesterService.of("svc-wms"))

    @Nested
    inner class `저장과 조회` {
        @Test
        fun `저장하면 식별자와 생성 시각이 부여되고 같은 값으로 조회된다`() =
            runBlocking<Unit> {
                val saved: InventoryHistory = adapter.save(history())

                val found: InventoryHistory? = adapter.findByIdempotencyKey(IdempotencyKey.of("svc-wms-inbound-1"), inventoryId)
                assertThat(saved.inventoryHistoryId).isNotNull()
                assertThat(saved.createdAt).isNotNull()
                assertThat(found).isEqualTo(saved)
                assertThat(found?.historyType).isEqualTo(HistoryType.INBOUND)
                assertThat(found?.quantityChange).isEqualTo(5)
                assertThat(found?.quantityAfter).isEqualTo(15)
                assertThat(found?.referenceType).isEqualTo(ReferenceType.INBOUND_ITEM)
                assertThat(found?.referenceId).isEqualTo(77L)
                assertThat(found?.requesterService?.value).isEqualTo("svc-wms")
            }

        @Test
        fun `생성자는 현재 Actor로 기록되고 수정 컬럼은 없다`() =
            runBlocking<Unit> {
                adapter.save(history())

                val row: Map<String, Any?> =
                    databaseClient
                        .sql("SELECT created_by FROM inventory_history")
                        .fetch()
                        .one()
                        .awaitFirst()
                assertThat(row["created_by"]).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            }

        @Test
        fun `없는 키는 null이다`() =
            runBlocking<Unit> {
                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("none"), inventoryId)).isNull()
                assertThat(adapter.findAllByIdempotencyKey(IdempotencyKey.of("none"))).isEmpty()
            }

        @Test
        fun `한 요청이 여러 행을 바꾼 이력은 재고 행 식별자 오름차순으로 조회된다`() =
            runBlocking<Unit> {
                adapter.save(history(inventoryId = otherInventoryId))
                adapter.save(history(inventoryId = inventoryId))

                val all: List<InventoryHistory> = adapter.findAllByIdempotencyKey(IdempotencyKey.of("svc-wms-inbound-1"))

                assertThat(all.map { it.inventoryId }).containsExactly(inventoryId, otherInventoryId)
            }

        @Test
        fun `이미 저장된 이력은 다시 저장할 수 없다`() =
            runBlocking<Unit> {
                val saved: InventoryHistory = adapter.save(history())

                assertThrows<IllegalStateException> { adapter.save(saved) }
            }
    }

    @Nested
    inner class `멱등 키` {
        @Test
        fun `같은 멱등 키와 재고 행의 이력은 중복으로 변환된다`() =
            runBlocking<Unit> {
                adapter.save(history())

                assertThrows<DuplicateIdempotencyKeyException> { adapter.save(history(change = 9, after = 19)) }
            }

        @Test
        fun `같은 멱등 키라도 재고 행이 다르면 저장된다`() =
            runBlocking<Unit> {
                adapter.save(history(inventoryId = inventoryId))

                val other: InventoryHistory = adapter.save(history(inventoryId = otherInventoryId))

                assertThat(other.inventoryHistoryId).isNotNull()
            }

        @Test
        fun `멱등 키는 대소문자를 구분한다`() =
            runBlocking<Unit> {
                adapter.save(history(key = "abc-1"))
                adapter.save(history(key = "ABC-1"))

                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("abc-1"), inventoryId)).isNotNull()
                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("ABC-1"), inventoryId)).isNotNull()
                assertThat(adapter.findByIdempotencyKey(IdempotencyKey.of("Abc-1"), inventoryId)).isNull()
            }

        @Test
        fun `같은 멱등 키 50개가 동시에 저장되면 한 건만 성공한다`() =
            runBlocking<Unit> {
                val failures: List<Throwable?> =
                    (1..50)
                        .map { async(Dispatchers.Default) { runCatching { adapter.save(history()) }.exceptionOrNull() } }
                        .awaitAll()

                assertThat(failures.count { it == null }).isEqualTo(1)
                assertThat(failures.filterNotNull()).hasSize(49).allMatch { it is DuplicateIdempotencyKeyException }
                assertThat(adapter.findAllByIdempotencyKey(IdempotencyKey.of("svc-wms-inbound-1"))).hasSize(1)
            }
    }

    @Nested
    inner class `스키마` {
        private suspend fun checkValues(constraintName: String): List<String> {
            val clause: String =
                databaseClient
                    .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = :name")
                    .bind("name", constraintName)
                    .fetch()
                    .one()
                    .awaitFirst()["CHECK_CLAUSE"] as String
            return Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()
        }

        @Test
        fun `HistoryType과 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(checkValues("ck_history_type")).containsExactlyInAnyOrderElementsOf(HistoryType.entries.map { it.name })
            }

        @Test
        fun `ReferenceType과 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                assertThat(
                    checkValues("ck_history_reference_type"),
                ).containsExactlyInAnyOrderElementsOf(ReferenceType.entries.map { it.name })
            }

        @Test
        fun `모든 이력 유형과 원인 문서 유형을 저장할 수 있다`() =
            runBlocking<Unit> {
                HistoryType.entries.forEachIndexed { index: Int, type: HistoryType ->
                    val change: Int = if (type == HistoryType.OUTBOUND || type == HistoryType.DISPOSAL) -1 else 1
                    adapter.save(history(key = "type-$index", type = type, change = change, after = 5))
                }
                ReferenceType.entries.forEachIndexed { index: Int, reference: ReferenceType ->
                    adapter.save(history(key = "ref-$index", reference = reference))
                }

                val count: Long =
                    databaseClient
                        .sql("SELECT COUNT(*) AS c FROM inventory_history")
                        .fetch()
                        .one()
                        .awaitFirst()["c"]
                        .toString()
                        .toLong()
                assertThat(count).isEqualTo((HistoryType.entries.size + ReferenceType.entries.size).toLong())
            }
    }
}
