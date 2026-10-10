package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.application.port.`in`.ConfirmInboundUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ConfirmInboundCommand
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.product.application.port.`in`.RegisterProductUseCase
import com.dozycoffee.inventory.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 실제 MySQL로 수량·예약·상품의 변경이 같은 트랜잭션에서 Outbox에 이벤트를 남기는지 검증한다 */
@InventoryIntegrationTest
class OutboxEventRecordingIntegrationTest {
    @Autowired
    private lateinit var confirmInboundUseCase: ConfirmInboundUseCase

    @Autowired
    private lateinit var registerProductUseCase: RegisterProductUseCase

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase

    @Autowired
    private lateinit var fulfillReservationUseCase: FulfillReservationUseCase

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

    private suspend fun types(): List<String> =
        databaseClient
            .sql("SELECT event_type FROM outbox_event ORDER BY outbox_event_id")
            .fetch()
            .all()
            .collectList()
            .awaitFirst()
            .map { it["event_type"].toString() }

    private suspend fun scalar(sql: String): String =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()

    @Test
    fun `상품 등록은 상품 이벤트를 남긴다`() =
        runBlocking<Unit> {
            registerProductUseCase.register(RegisterProductCommand("BEAN-001", "원두", ProductCategory.BEAN, "KG", 180))

            assertThat(types()).containsExactly("PRODUCT_REGISTERED")
            assertThat(scalar("SELECT aggregate_type FROM outbox_event")).isEqualTo("PRODUCT")
            assertThat(scalar("SELECT status FROM outbox_event")).isEqualTo("PENDING")
        }

    @Test
    fun `입고 확정은 재고 증가 이벤트를 남기고 재요청은 중복으로 남기지 않는다`() =
        runBlocking<Unit> {
            val productId: Long = fixture.seedProduct()
            val command =
                ConfirmInboundCommand(
                    10L,
                    productId,
                    5,
                    QualityStatus.NORMAL,
                    "LOT-A",
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2027, 3, 1),
                    77L,
                    "wms-inbound-77",
                    "svc-wms",
                )

            confirmInboundUseCase.confirm(command)
            confirmInboundUseCase.confirm(command)

            assertThat(types()).containsExactly("INVENTORY_INCREASED")
            assertThat(scalar("SELECT partition_key FROM outbox_event")).isEqualTo("10:$productId")
            assertThat(scalar("SELECT JSON_EXTRACT(payload, '$.quantityAfter') FROM outbox_event")).isEqualTo("5")
        }

    @Test
    fun `예약 생성부터 출고 확정까지 이벤트가 순서대로 남고 같은 예약은 같은 파티션 키다`() =
        runBlocking<Unit> {
            val productId: Long = fixture.seedProduct()
            val inventoryId: Long =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId),
                    quantity = 10,
                )
            val created =
                createReservationUseCase.create(
                    CreateReservationCommand(
                        10L,
                        "OMS",
                        "ORDER-1",
                        listOf(CreateReservationCommand.Item(productId, 6)),
                        LocalDateTime.now(clock).plusMinutes(30),
                        "key-1",
                        "svc-oms",
                    ),
                )
            confirmReservationUseCase.confirm(created.reservationId)
            fulfillReservationUseCase.fulfill(
                FulfillReservationCommand(
                    created.reservationId,
                    listOf(FulfillReservationCommand.Allocation(inventoryId, 6)),
                    "wms-outbound-1",
                    "svc-wms",
                ),
            )

            assertThat(
                types(),
            ).containsExactly("RESERVATION_CREATED", "RESERVATION_CONFIRMED", "INVENTORY_DECREASED", "RESERVATION_FULFILLED")
            assertThat(scalar("SELECT COUNT(DISTINCT partition_key) FROM outbox_event WHERE aggregate_type = 'RESERVATION'")).isEqualTo("1")
            assertThat(
                scalar("SELECT DISTINCT partition_key FROM outbox_event WHERE aggregate_type = 'RESERVATION'"),
            ).isEqualTo("10:${created.reservationId}")
        }

    @Test
    fun `업무 처리가 롤백되면 이벤트도 남지 않는다`() =
        runBlocking<Unit> {
            val productId: Long = fixture.seedProduct()
            val inventoryId: Long =
                fixture.insertInventory(
                    10L,
                    productId,
                    checkNotNull(fixture.seedLot(productId, "A", LocalDate.of(2027, 1, 1)).lotId),
                    quantity = 10,
                )
            val created =
                createReservationUseCase.create(
                    CreateReservationCommand(
                        10L,
                        "OMS",
                        "ORDER-1",
                        listOf(CreateReservationCommand.Item(productId, 6)),
                        LocalDateTime.now(clock).plusMinutes(30),
                        "key-1",
                        "svc-oms",
                    ),
                )
            confirmReservationUseCase.confirm(created.reservationId)
            databaseClient
                .sql(
                    "UPDATE inventory SET reserved_quantity = 0 WHERE inventory_id = $inventoryId",
                ).fetch()
                .rowsUpdated()
                .awaitFirst()
            val before: List<String> = types()

            runCatching {
                fulfillReservationUseCase.fulfill(
                    FulfillReservationCommand(
                        created.reservationId,
                        listOf(FulfillReservationCommand.Allocation(inventoryId, 6)),
                        "wms-outbound-1",
                        "svc-wms",
                    ),
                )
            }

            assertThat(types()).isEqualTo(before)
        }
}
