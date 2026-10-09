package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.inventory.adapter.out.persistence.LotPersistenceAdapter
import com.dozycoffee.inventory.inventory.fixture.InventoryDbFixture
import com.dozycoffee.inventory.outbox.application.port.`in`.PublishOutboxEventsUseCase
import com.dozycoffee.inventory.product.application.port.`in`.RegisterProductUseCase
import com.dozycoffee.inventory.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import com.dozycoffee.inventory.support.KafkaTestContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 실제 MySQL과 Kafka로 Outbox에 쌓인 이벤트가 순서대로 한 번씩 발행되고 발행 완료로 표시되는지 검증한다. 발행기는 직접 호출한다 */
@InventoryIntegrationTest
@TestPropertySource(properties = ["inventory.outbox.publisher.topic-prefix=e2e"])
class OutboxPublishIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun kafka(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { KafkaTestContainer.bootstrapServers }
        }
    }

    @Autowired
    private lateinit var publishOutboxEventsUseCase: PublishOutboxEventsUseCase

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

    private suspend fun scalar(sql: String): String =
        databaseClient
            .sql(sql)
            .fetch()
            .one()
            .awaitFirst()
            .values
            .first()
            .toString()

    private fun eventId(record: ConsumerRecord<String, String>): Long =
        String(record.headers().lastHeader("event-id").value(), Charsets.UTF_8).toLong()

    private suspend fun reservationLifecycle() {
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
    }

    @Test
    fun `쌓인 이벤트를 집계별 토픽으로 순서대로 발행하고 발행 완료로 표시한다`() =
        runBlocking<Unit> {
            reservationLifecycle()
            val ids: List<Long> =
                databaseClient
                    .sql(
                        "SELECT outbox_event_id FROM outbox_event ORDER BY outbox_event_id",
                    ).fetch()
                    .all()
                    .collectList()
                    .awaitFirst()
                    .map { it["outbox_event_id"].toString().toLong() }

            assertThat(publishOutboxEventsUseCase.publishPending(100)).isEqualTo(4)

            val records: List<ConsumerRecord<String, String>> = KafkaTestContainer.consume(listOf("e2e.inventory", "e2e.reservation"), 4)
            assertThat(records).hasSize(4)
            assertThat(records.map { it.topic() }.groupingBy { it }.eachCount()).isEqualTo(
                mapOf(
                    "e2e.reservation" to 3,
                    "e2e.inventory" to 1,
                ),
            )
            val reservation: List<ConsumerRecord<String, String>> = records.filter { it.topic() == "e2e.reservation" }
            assertThat(reservation.map { eventId(it) }).isEqualTo(reservation.map { eventId(it) }.sorted())
            assertThat(reservation.map { it.key() }.toSet()).hasSize(1)
            assertThat(records.map { eventId(it) }.sorted()).isEqualTo(ids)
            assertThat(reservation.map { it.value() }).allMatch { it.contains("\"reservationId\"") }
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED' AND published_at IS NOT NULL")).isEqualTo("4")
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")).isEqualTo("0")
        }

    @Test
    fun `이미 발행한 이벤트는 다시 발행하지 않는다`() =
        runBlocking<Unit> {
            reservationLifecycle()
            publishOutboxEventsUseCase.publishPending(100)

            assertThat(publishOutboxEventsUseCase.publishPending(100)).isEqualTo(0)
        }

    @Test
    fun `묶음 크기만큼씩 나눠 발행한다`() =
        runBlocking<Unit> {
            reservationLifecycle()

            assertThat(publishOutboxEventsUseCase.publishPending(3)).isEqualTo(3)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")).isEqualTo("1")
            assertThat(publishOutboxEventsUseCase.publishPending(3)).isEqualTo(1)
        }

    @Test
    fun `발행기가 동시에 여러 개 돌아도 락을 잡은 하나만 발행해 이벤트가 한 번씩만 나간다`() =
        runBlocking<Unit> {
            (1..30).forEach { registerProductUseCase.register(RegisterProductCommand("P-$it", "상품 $it", ProductCategory.BEAN, "KG", 180)) }

            val published: List<Int> = (1..5).map { async(Dispatchers.IO) { publishOutboxEventsUseCase.publishPending(100) } }.awaitAll()

            assertThat(published.sum()).isEqualTo(30)
            val records: List<ConsumerRecord<String, String>> = KafkaTestContainer.consume(listOf("e2e.product"), 30)
            assertThat(records.map { eventId(it) }.toSet()).hasSize(30)
            assertThat(scalar("SELECT COUNT(*) FROM outbox_event WHERE status = 'PENDING'")).isEqualTo("0")
        }
}
