package com.dozycoffee.inventory.product.adapter.out.event

import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.application.port.out.ProductEvent
import com.dozycoffee.inventory.product.application.port.out.ProductEventType
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verifyBlocking
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@ExtendWith(MockitoExtension::class)
class OutboxProductEventPublisherTest {
    @Mock
    private lateinit var recordOutboxEventUseCase: RecordOutboxEventUseCase

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private lateinit var publisher: OutboxProductEventPublisher

    @BeforeEach
    fun setUp() {
        publisher =
            OutboxProductEventPublisher(
                recordOutboxEventUseCase,
                mapper,
                Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneId.of("Asia/Seoul")),
            )
    }

    private fun product(shelfLifeDays: Int? = 180): ProductResult =
        ProductResult(100L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", shelfLifeDays, ProductStatus.ACTIVE)

    private suspend fun recorded(event: ProductEvent): RecordOutboxEventCommand {
        publisher.publish(event)
        val command = argumentCaptor<RecordOutboxEventCommand>()
        verifyBlocking(recordOutboxEventUseCase) { record(command.capture()) }
        return command.firstValue
    }

    @Test
    fun `상품 집계의 이벤트로 상품 ID를 파티션 키로 저장한다`() =
        runBlocking<Unit> {
            val command: RecordOutboxEventCommand = recorded(ProductEvent(ProductEventType.REGISTERED, product()))

            assertEquals("PRODUCT", command.aggregateType)
            assertEquals(100L, command.aggregateId)
            assertEquals("PRODUCT_REGISTERED", command.eventType)
            assertEquals("100", command.partitionKey)
        }

    @Test
    fun `payload에 상품 스냅샷을 담고 유통기한 일수가 없으면 null이다`() =
        runBlocking<Unit> {
            val json: JsonNode =
                mapper.readTree(
                    recorded(ProductEvent(ProductEventType.STATUS_CHANGED, product(shelfLifeDays = null))).payload,
                )

            assertEquals("PRODUCT_STATUS_CHANGED", json["eventType"].asString())
            assertEquals(100L, json["productId"].asLong())
            assertEquals("BEAN-001", json["productCode"].asString())
            assertEquals("원두", json["productName"].asString())
            assertEquals("BEAN", json["category"].asString())
            assertEquals("KG", json["unit"].asString())
            assertTrue(json["shelfLifeDays"].isNull)
            assertEquals("ACTIVE", json["productStatus"].asString())
            assertEquals("2026-10-08T12:00:00+09:00", json["occurredAt"].asString())
        }
}
