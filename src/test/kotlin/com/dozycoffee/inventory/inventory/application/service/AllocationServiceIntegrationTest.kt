package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ReleaseInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.support.InventoryIntegrationTest
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.LocalDate

/** 실제 MySQL로 Lot 할당의 정확성과 동시 요청에서도 초과 예약이 없음을 검증한다. 호출자 트랜잭션은 테스트가 연다 */
@InventoryIntegrationTest
class AllocationServiceIntegrationTest {
    @Autowired
    private lateinit var allocateInventoryUseCase: AllocateInventoryUseCase

    @Autowired
    private lateinit var releaseInventoryUseCase: ReleaseInventoryUseCase

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private suspend fun row(
        lot: String,
        expiration: LocalDate?,
        quantity: Int,
    ): Long = fixture.insertInventory(10L, productId, checkNotNull(fixture.seedLot(productId, lot, expiration).lotId), quantity = quantity)

    private suspend fun reserved(inventoryId: Long): Int =
        databaseClient
            .sql("SELECT reserved_quantity AS r FROM inventory WHERE inventory_id = :id")
            .bind("id", inventoryId)
            .fetch()
            .one()
            .awaitFirst()["r"]
            .toString()
            .toInt()

    private suspend fun totalReserved(): Long =
        databaseClient
            .sql("SELECT COALESCE(SUM(reserved_quantity), 0) AS r FROM inventory")
            .fetch()
            .one()
            .awaitFirst()["r"]
            .toString()
            .toLong()

    /** 예약 서비스가 하는 일을 흉내 낸다. 트랜잭션 하나에 한 번 시도하고, 경합 패배는 새 트랜잭션에서 다시 시도한다 */
    private suspend fun allocate(vararg items: Pair<Long, Int>): AllocationResult {
        val command = AllocateInventoryCommand(10L, items.map { AllocateInventoryCommand.Item(it.first, it.second) })
        repeat(RETRIES) {
            try {
                return checkNotNull(transactionalOperator.executeAndAwait { allocateInventoryUseCase.allocate(command) })
            } catch (e: AllocationConflictException) {
                // 새 트랜잭션에서 새 스냅샷으로 다시 계획한다
            }
        }
        throw AllocationConflictException()
    }

    private companion object {
        const val RETRIES: Int = 30
    }

    @Nested
    inner class `할당` {
        @Test
        fun `유통기한이 이른 Lot부터 예약 수량이 늘어난다`() =
            runBlocking<Unit> {
                val late: Long = row("LATE", LocalDate.of(2027, 6, 1), 10)
                val early: Long = row("EARLY", LocalDate.of(2027, 1, 1), 4)

                val result: AllocationResult = allocate(productId to 6)

                assertThat(
                    result.items
                        .single()
                        .lots
                        .map { it.inventoryId to it.quantity },
                ).containsExactly(early to 4, late to 2)
                assertThat(reserved(early)).isEqualTo(4)
                assertThat(reserved(late)).isEqualTo(2)
            }

        @Test
        fun `모자라면 호출자 트랜잭션이 롤백되어 어떤 상품도 예약되지 않는다`() =
            runBlocking<Unit> {
                row("A", LocalDate.of(2027, 1, 1), 10)
                val other: Long = fixture.seedProduct("BEAN-002")
                fixture.insertInventory(10L, other, checkNotNull(fixture.seedLot(other, "B").lotId), quantity = 3)

                assertThrows<InsufficientAvailableQuantityException> { allocate(productId to 5, other to 4) }

                assertThat(totalReserved()).isEqualTo(0L)
            }
    }

    @Nested
    inner class `해제` {
        @Test
        fun `예약 수량이 가용 수량으로 되돌아온다`() =
            runBlocking<Unit> {
                val late: Long = row("LATE", LocalDate.of(2027, 6, 1), 10)
                val early: Long = row("EARLY", LocalDate.of(2027, 1, 1), 4)
                allocate(productId to 6)

                transactionalOperator.executeAndAwait {
                    releaseInventoryUseCase.release(
                        ReleaseInventoryCommand(listOf(ReleaseInventoryCommand.Item(late, 2), ReleaseInventoryCommand.Item(early, 4))),
                    )
                }

                assertThat(reserved(early)).isEqualTo(0)
                assertThat(reserved(late)).isEqualTo(0)
                assertThat(
                    allocate(productId to 14)
                        .items
                        .single()
                        .lots
                        .sumOf { it.quantity },
                ).isEqualTo(14)
            }

        @Test
        fun `예약 수량보다 많이 되돌리려 하면 실패하고 롤백되어 앞서 되돌린 수량도 그대로다`() =
            runBlocking<Unit> {
                val late: Long = row("LATE", LocalDate.of(2027, 6, 1), 10)
                val early: Long = row("EARLY", LocalDate.of(2027, 1, 1), 4)
                allocate(productId to 6)

                assertThrows<InsufficientReservedQuantityException> {
                    transactionalOperator.executeAndAwait {
                        releaseInventoryUseCase.release(
                            ReleaseInventoryCommand(listOf(ReleaseInventoryCommand.Item(early, 4), ReleaseInventoryCommand.Item(late, 3))),
                        )
                    }
                }

                assertThat(reserved(early)).isEqualTo(4)
                assertThat(reserved(late)).isEqualTo(2)
            }
    }

    @Nested
    inner class `동시성` {
        @Test
        fun `같은 Lot에 8개씩 20개 동시 요청해도 재고 100개를 넘겨 예약하지 않는다`() =
            runBlocking<Unit> {
                val first: Long = row("A", LocalDate.of(2027, 1, 1), 60)
                val second: Long = row("B", LocalDate.of(2027, 2, 1), 40)

                val results: List<Result<AllocationResult>> =
                    (1..20).map { async(Dispatchers.IO) { runCatching { allocate(productId to 8) } } }.awaitAll()

                val succeeded: Int = results.count { it.isSuccess }
                assertThat(succeeded).isEqualTo(12)
                assertThat(
                    results.filter { it.isFailure }.map { it.exceptionOrNull() },
                ).allMatch { it is InsufficientAvailableQuantityException }
                assertThat(reserved(first) + reserved(second)).isEqualTo(96)
                assertThat(totalReserved()).isEqualTo(96L)
            }

        @Test
        fun `여러 상품을 서로 다른 순서로 동시에 요청해도 데드락 없이 모두 처리된다`() =
            runBlocking<Unit> {
                row("A", LocalDate.of(2027, 1, 1), 100)
                val other: Long = fixture.seedProduct("BEAN-002")
                fixture.insertInventory(10L, other, checkNotNull(fixture.seedLot(other, "B").lotId), quantity = 100)

                val results: List<Result<AllocationResult>> =
                    (1..20)
                        .map { i: Int ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    if (i % 2 ==
                                        0
                                    ) {
                                        allocate(productId to 1, other to 1)
                                    } else {
                                        allocate(other to 1, productId to 1)
                                    }
                                }
                            }
                        }.awaitAll()

                assertThat(results.filter { it.isFailure }).isEmpty()
                assertThat(totalReserved()).isEqualTo(40L)
            }
    }
}
