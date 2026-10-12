package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmReturnUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmReturnCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ReturnResult
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.product.domain.exception.ProductNotFoundException
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
import java.time.LocalDate

/** 실제 MySQL로 반품 복귀의 수량 증가, 이력, 이벤트, 롤백, 멱등, 동시 요청을 검증한다. 서비스가 트랜잭션을 직접 열고 반품은 이미 등록된 Lot만 받는다 */
@InventoryIntegrationTest
class ReturnServiceIntegrationTest {
    @Autowired
    private lateinit var confirmReturnUseCase: ConfirmReturnUseCase

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var lotPersistenceAdapter: LotPersistenceAdapter

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private var lotId: Long = 0L

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, lotPersistenceAdapter)
            fixture.cleanUp()
            productId = fixture.seedProduct()
            lotId = checkNotNull(fixture.seedLot(productId, "LOT-A", LocalDate.of(2027, 1, 1)).lotId)
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun command(
        key: String = "wms-return-5",
        quantity: Int = 4,
        quality: QualityStatus = QualityStatus.NORMAL,
        referenceId: Long = 5L,
        lotNumber: String = "LOT-A",
        product: Long = productId,
    ): ConfirmReturnCommand = ConfirmReturnCommand(10L, product, lotNumber, quality, quantity, referenceId, key, "svc-wms")

    private suspend fun scalar(sql: String): Long =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()
            .toLong()

    private suspend fun quantity(inventoryId: Long): Long = scalar("SELECT quantity FROM inventory WHERE inventory_id = $inventoryId")

    @Nested
    inner class `반영` {
        @Test
        fun `기존 행에 더하고 RETURN 이력과 증가 이벤트를 남긴다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10)

                val result: ReturnResult = confirmReturnUseCase.confirm(command())

                assertThat(result.inventoryId).isEqualTo(row)
                assertThat(result.quantityChange).isEqualTo(4)
                assertThat(result.quantityAfter).isEqualTo(14)
                assertThat(quantity(row)).isEqualTo(14L)
                assertThat(
                    scalar(
                        "SELECT COUNT(*) FROM inventory_history WHERE history_type = 'RETURN' AND reference_type = 'RETURN_ITEM' " +
                            "AND reference_id = 5 AND quantity_change = 4 AND quantity_after = 14",
                    ),
                ).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INVENTORY_INCREASED'")).isEqualTo(1L)
            }

        @Test
        fun `재고 행이 없으면 만들고 불량 판정은 불량 품질 행에 반영한다`() =
            runBlocking<Unit> {
                val normal: ReturnResult = confirmReturnUseCase.confirm(command(key = "k-1", referenceId = 1L))
                val defective: ReturnResult =
                    confirmReturnUseCase.confirm(
                        command(key = "k-2", quality = QualityStatus.DEFECTIVE, referenceId = 2L),
                    )

                assertThat(normal.quantityAfter).isEqualTo(4)
                assertThat(defective.quantityAfter).isEqualTo(4)
                assertThat(defective.inventoryId).isNotEqualTo(normal.inventoryId)
                assertThat(scalar("SELECT COUNT(*) FROM inventory WHERE lot_id = $lotId AND quality_status = 'DEFECTIVE'")).isEqualTo(1L)
            }

        @Test
        fun `비활성 상품의 반품도 반영한다`() =
            runBlocking<Unit> {
                databaseClient
                    .sql("UPDATE product SET product_status = 'INACTIVE' WHERE product_id = $productId")
                    .fetch()
                    .rowsUpdated()
                    .awaitFirst()

                assertThat(confirmReturnUseCase.confirm(command()).quantityAfter).isEqualTo(4)
            }
    }

    @Nested
    inner class `반려` {
        @Test
        fun `폐기 예정 품질 상태는 거부하고 아무것도 남지 않는다`() =
            runBlocking<Unit> {
                assertThrows<com.dozycoffee.inventory.global.error.InvalidDomainValueException> {
                    confirmReturnUseCase.confirm(command(quality = QualityStatus.DISPOSAL_SCHEDULED))
                }

                assertThat(scalar("SELECT COUNT(*) FROM inventory")).isEqualTo(0L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(0L)
            }

        @Test
        fun `등록되지 않은 Lot은 거부하고 Lot이나 재고를 만들지 않는다`() =
            runBlocking<Unit> {
                assertThrows<LotNotFoundException> { confirmReturnUseCase.confirm(command(lotNumber = "NOPE")) }

                assertThat(scalar("SELECT COUNT(*) FROM lot")).isEqualTo(1L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory")).isEqualTo(0L)
            }

        @Test
        fun `없는 상품은 거부한다`() =
            runBlocking<Unit> {
                assertThrows<ProductNotFoundException> { confirmReturnUseCase.confirm(command(product = 999_999L)) }
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 멱등 키의 재요청은 다시 더하지 않고 처음과 같은 결과를 준다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 10)
                val first: ReturnResult = confirmReturnUseCase.confirm(command())
                confirmReturnUseCase.confirm(command(key = "wms-return-6", quantity = 1, referenceId = 6L))

                val again: ReturnResult = confirmReturnUseCase.confirm(command())

                assertThat(again).isEqualTo(first)
                assertThat(quantity(row)).isEqualTo(15L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `같은 멱등 키에 다른 내용이면 거부한다`() =
            runBlocking<Unit> {
                confirmReturnUseCase.confirm(command())

                assertThrows<IdempotencyKeyConflictException> { confirmReturnUseCase.confirm(command(quantity = 5)) }
            }

        @Test
        fun `같은 멱등 키 20개가 동시에 들어와도 한 번만 더하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 100)

                val results: List<ReturnResult> =
                    (1..20)
                        .map {
                            async(
                                Dispatchers.IO,
                            ) { confirmReturnUseCase.confirm(command()) }
                        }.awaitAll()

                assertThat(results.toSet()).hasSize(1)
                assertThat(quantity(row)).isEqualTo(104L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(1L)
            }

        @Test
        fun `서로 다른 키의 반품이 같은 행에 몰려도 모두 반영되어 합계가 맞는다`() =
            runBlocking<Unit> {
                val row: Long = fixture.insertInventory(10L, productId, lotId, "NORMAL", quantity = 0)

                (1..20)
                    .map { i: Int ->
                        async(Dispatchers.IO) { confirmReturnUseCase.confirm(command("return-$i", 1, referenceId = i.toLong())) }
                    }.awaitAll()

                assertThat(quantity(row)).isEqualTo(20L)
                assertThat(scalar("SELECT COUNT(*) FROM inventory_history")).isEqualTo(20L)
            }
    }
}
