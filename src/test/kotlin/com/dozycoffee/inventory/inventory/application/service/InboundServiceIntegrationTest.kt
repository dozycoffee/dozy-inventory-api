package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmInboundUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InboundResult
import com.dozycoffee.inventory.inventory.domain.enumeration.QualityStatus
import com.dozycoffee.inventory.inventory.domain.exception.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.domain.exception.LotMismatchException
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

/** 실제 MySQL과 전체 컨텍스트로 입고 확정의 트랜잭션, 멱등, 동시성을 검증한다 */
@InventoryIntegrationTest
class InboundServiceIntegrationTest {
    @Autowired
    private lateinit var confirmInboundUseCase: ConfirmInboundUseCase

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

    private fun command(
        key: String = "wms-inbound-item-1",
        quantity: Int = 5,
        referenceId: Long = 1L,
        lotNumber: String = "LOT-A",
        quality: QualityStatus = QualityStatus.NORMAL,
        expirationDate: LocalDate? = LocalDate.of(2027, 3, 1),
    ): ConfirmInboundCommand =
        ConfirmInboundCommand(
            10L,
            productId,
            quantity,
            quality,
            lotNumber,
            LocalDate.of(2026, 9, 1),
            expirationDate,
            referenceId,
            key,
            "svc-wms",
        )

    private suspend fun count(table: String): Long =
        databaseClient
            .sql("SELECT COUNT(*) AS c FROM $table")
            .fetch()
            .one()
            .awaitFirst()["c"]
            .toString()
            .toLong()

    private suspend fun totalQuantity(): Long =
        databaseClient
            .sql("SELECT COALESCE(SUM(quantity), 0) AS q FROM inventory")
            .fetch()
            .one()
            .awaitFirst()["q"]
            .toString()
            .toLong()

    @Nested
    inner class `반영` {
        @Test
        fun `처음 입고는 Lot과 재고 행과 이력을 만들고 두 번째 입고는 같은 행에 더한다`() =
            runBlocking<Unit> {
                val first: InboundResult = confirmInboundUseCase.confirm(command(key = "k-1", quantity = 5, referenceId = 1L))
                val second: InboundResult = confirmInboundUseCase.confirm(command(key = "k-2", quantity = 3, referenceId = 2L))

                assertThat(first.quantityAfter).isEqualTo(5)
                assertThat(second.quantityAfter).isEqualTo(8)
                assertThat(second.inventoryId).isEqualTo(first.inventoryId)
                assertThat(second.lotId).isEqualTo(first.lotId)
                assertThat(count("lot")).isEqualTo(1L)
                assertThat(count("inventory")).isEqualTo(1L)
                assertThat(count("inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `품질 상태가 다르면 다른 재고 행에 반영되고 Lot은 하나다`() =
            runBlocking<Unit> {
                val normal: InboundResult = confirmInboundUseCase.confirm(command(key = "k-1", quality = QualityStatus.NORMAL))
                val defective: InboundResult =
                    confirmInboundUseCase.confirm(
                        command(key = "k-2", quality = QualityStatus.DEFECTIVE, referenceId = 2L),
                    )

                assertThat(defective.inventoryId).isNotEqualTo(normal.inventoryId)
                assertThat(defective.lotId).isEqualTo(normal.lotId)
                assertThat(count("lot")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `멱등` {
        @Test
        fun `같은 키의 재요청은 수량을 다시 더하지 않고 처음 응답과 같은 결과를 반환한다`() =
            runBlocking<Unit> {
                val first: InboundResult = confirmInboundUseCase.confirm(command(key = "k-1", quantity = 5))
                confirmInboundUseCase.confirm(command(key = "k-2", quantity = 3, referenceId = 2L))

                val replay: InboundResult = confirmInboundUseCase.confirm(command(key = "k-1", quantity = 5))

                assertThat(replay).isEqualTo(first)
                assertThat(replay.quantityAfter).describedAs("처음 응답 시점의 수량").isEqualTo(5)
                assertThat(totalQuantity()).isEqualTo(8L)
                assertThat(count("inventory_history")).isEqualTo(2L)
            }

        @Test
        fun `같은 키에 다른 내용이 오면 거부하고 아무것도 바꾸지 않는다`() =
            runBlocking<Unit> {
                confirmInboundUseCase.confirm(command(key = "k-1", quantity = 5))

                assertThrows<IdempotencyKeyConflictException> { confirmInboundUseCase.confirm(command(key = "k-1", quantity = 6)) }

                assertThat(totalQuantity()).isEqualTo(5L)
                assertThat(count("inventory_history")).isEqualTo(1L)
            }
    }

    @Nested
    inner class `반려` {
        @Test
        fun `기존 Lot과 유통기한이 다르면 거부하고 수량과 이력은 그대로다`() =
            runBlocking<Unit> {
                confirmInboundUseCase.confirm(command(key = "k-1"))

                assertThrows<LotMismatchException> {
                    confirmInboundUseCase.confirm(command(key = "k-2", referenceId = 2L, expirationDate = LocalDate.of(2027, 4, 1)))
                }

                assertThat(totalQuantity()).isEqualTo(5L)
                assertThat(count("inventory_history")).isEqualTo(1L)
            }

        @Test
        fun `상품이 없으면 거부하고 Lot도 만들지 않는다`() =
            runBlocking<Unit> {
                assertThrows<ProductNotFoundException> { confirmInboundUseCase.confirm(command().copy(productId = 999_999L)) }

                assertThat(count("lot")).isEqualTo(0L)
                assertThat(count("inventory")).isEqualTo(0L)
            }
    }

    @Nested
    inner class `동시성` {
        @Test
        fun `같은 멱등 키 50개가 동시에 들어와도 한 번만 반영하고 모두 같은 결과를 받는다`() =
            runBlocking<Unit> {
                val results: List<Result<InboundResult>> =
                    (1..50)
                        .map {
                            async(
                                Dispatchers.Default,
                            ) { runCatching { confirmInboundUseCase.confirm(command(key = "same-key", quantity = 4)) } }
                        }.awaitAll()

                assertThat(results.mapNotNull { it.exceptionOrNull() }).describedAs("실패한 요청").isEmpty()
                assertThat(results.map { it.getOrThrow() }.toSet()).hasSize(1)
                assertThat(totalQuantity()).isEqualTo(4L)
                assertThat(count("inventory_history")).isEqualTo(1L)
            }

        @Test
        fun `같은 Lot이 처음 들어오는 요청 50개가 동시에 들어와도 Lot은 하나이고 합계가 정확하다`() =
            runBlocking<Unit> {
                val results: List<Result<InboundResult>> =
                    (1..50)
                        .map { index: Int ->
                            async(Dispatchers.Default) {
                                runCatching {
                                    confirmInboundUseCase.confirm(
                                        command(key = "key-$index", quantity = 2, referenceId = index.toLong()),
                                    )
                                }
                            }
                        }.awaitAll()

                assertThat(results.mapNotNull { it.exceptionOrNull() }).describedAs("실패한 요청").isEmpty()
                assertThat(count("lot")).isEqualTo(1L)
                assertThat(count("inventory")).isEqualTo(1L)
                assertThat(count("inventory_history")).isEqualTo(50L)
                assertThat(totalQuantity()).isEqualTo(100L)
                assertThat(results.map { it.getOrThrow().quantityAfter }.toSet()).describedAs("처리 후 수량은 모두 다르다").hasSize(50)
            }
    }
}
