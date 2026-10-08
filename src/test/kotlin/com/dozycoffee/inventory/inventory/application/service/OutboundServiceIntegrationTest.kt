package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.GetOutboundResultUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ShipInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.support.InventoryIntegrationTest
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

/** 실제 MySQL로 출고 반영의 수량, 이력, 롤백, 처리 후 수량 조회를 검증한다. 호출자 트랜잭션은 테스트가 연다 */
@InventoryIntegrationTest
class OutboundServiceIntegrationTest {
    @Autowired
    private lateinit var shipInventoryUseCase: ShipInventoryUseCase

    @Autowired
    private lateinit var getOutboundResultUseCase: GetOutboundResultUseCase

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var first: Long = 0L
    private var second: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            val productId: Long = fixture.seedProduct()
            first =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId),
                    quantity = 10,
                    reservedQuantity = 4,
                )
            second =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "B", LocalDate.of(2027, 6, 1)).lotId),
                    quantity = 20,
                    reservedQuantity = 6,
                )
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun command(
        key: String = "wms-outbound-1",
        vararg items: ShipInventoryCommand.Item,
    ): ShipInventoryCommand = ShipInventoryCommand(items.toList(), key, 77L, "svc-wms")

    private suspend fun ship(command: ShipInventoryCommand): ShipResult =
        checkNotNull(
            transactionalOperator.executeAndAwait {
                shipInventoryUseCase.ship(command)
            },
        )

    private suspend fun row(
        id: Long,
        column: String,
    ): Long =
        databaseClient
            .sql(
                "SELECT $column FROM inventory WHERE inventory_id = $id",
            ).fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()
            .toLong()

    private suspend fun count(sql: String): Long =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()
            .toLong()

    @Nested
    inner class `출고 반영` {
        @Test
        fun `출고한 행은 총 수량과 예약 수량이 함께 줄고 OUTBOUND 이력이 남는다`() =
            runBlocking<Unit> {
                val result: ShipResult =
                    ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 4, 0), ShipInventoryCommand.Item(second, 6, 0))))

                assertThat(result.items).containsExactly(ShipResult.Item(first, 6), ShipResult.Item(second, 14))
                assertThat(row(first, "quantity")).isEqualTo(6L)
                assertThat(row(first, "reserved_quantity")).isEqualTo(0L)
                assertThat(row(second, "quantity")).isEqualTo(14L)
                assertThat(row(second, "reserved_quantity")).isEqualTo(0L)
                assertThat(
                    count(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'OUTBOUND' AND reference_type = 'RESERVATION' AND reference_id = 77",
                    ),
                ).isEqualTo(2L)
                assertThat(count("SELECT SUM(quantity_change) FROM inventory_history")).isEqualTo(-10L)
            }

        @Test
        fun `결품은 예약 수량만 되돌리고 총 수량은 그대로이며 이력이 없다`() =
            runBlocking<Unit> {
                val result: ShipResult =
                    ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 1, 3), ShipInventoryCommand.Item(second, 0, 6))))

                assertThat(result.items).containsExactly(ShipResult.Item(first, 9), ShipResult.Item(second, null))
                assertThat(row(first, "quantity")).isEqualTo(9L)
                assertThat(row(first, "reserved_quantity")).isEqualTo(0L)
                assertThat(row(second, "quantity")).isEqualTo(20L)
                assertThat(row(second, "reserved_quantity")).isEqualTo(0L)
                assertThat(count("SELECT COUNT(*) FROM inventory_history")).isEqualTo(1L)
            }

        @Test
        fun `나중에 처리 후 수량을 멱등 키로 다시 만들 수 있다`() =
            runBlocking<Unit> {
                ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 4, 0), ShipInventoryCommand.Item(second, 0, 6))))

                assertThat(getOutboundResultUseCase.getQuantitiesAfter("wms-outbound-1", 77L)).isEqualTo(mapOf(first to 6))
                assertThat(getOutboundResultUseCase.getQuantitiesAfter("wms-outbound-1", 78L)).isEmpty()
                assertThat(getOutboundResultUseCase.getQuantitiesAfter("other-key", 77L)).isEmpty()
            }
    }

    @Nested
    inner class `실패와 롤백` {
        @Test
        fun `예약 수량보다 많이 출고하려 하면 실패하고 앞서 반영한 행도 롤백된다`() =
            runBlocking<Unit> {
                assertThrows<InsufficientReservedQuantityException> {
                    ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 4, 0), ShipInventoryCommand.Item(second, 7, 0))))
                }

                assertThat(row(first, "quantity")).isEqualTo(10L)
                assertThat(row(first, "reserved_quantity")).isEqualTo(4L)
                assertThat(count("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `같은 멱등 키로 같은 재고 행을 다시 반영하면 중복 키 예외이고 수량도 롤백된다`() =
            runBlocking<Unit> {
                ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 2, 0))))

                assertThrows<DuplicateIdempotencyKeyException> { ship(command(items = arrayOf(ShipInventoryCommand.Item(first, 2, 0)))) }

                assertThat(row(first, "quantity")).isEqualTo(8L)
                assertThat(row(first, "reserved_quantity")).isEqualTo(2L)
                assertThat(count("SELECT COUNT(*) FROM inventory_history")).isEqualTo(1L)
            }
    }
}
