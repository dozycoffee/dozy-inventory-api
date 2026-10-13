package com.dozycoffee.inventory.access.adapter.`in`.event

import com.dozycoffee.inventory.access.application.port.out.WarehouseAccessRepository
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import com.dozycoffee.inventory.support.KafkaTestContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.Properties
import java.util.UUID

private const val TOPIC: String = "e2e.warehouse-access"

/** 실제 MySQL과 Kafka로 소속 변경 이벤트를 받아 사본을 교체하고, 형식이 잘못된 메시지는 DLT로 보내고 다음 메시지를 계속 처리하는지 검증한다 */
@InventoryIntegrationTest
@TestPropertySource(properties = ["inventory.warehouse-access.listener.enabled=true", "inventory.warehouse-access.listener.topic=$TOPIC"])
class WarehouseAccessEventListenerIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun kafka(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { KafkaTestContainer.bootstrapServers }
            // 리스너가 붙기 전에 토픽이 있어야 한다(없는 토픽은 메타데이터 갱신 주기까지 구독되지 않는다)
            AdminClient
                .create(Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers) })
                .use { admin ->
                    admin.createTopics(listOf(NewTopic(TOPIC, 1, 1.toShort()), NewTopic("$TOPIC.dlt", 1, 1.toShort()))).all().get()
                }
        }
    }

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var warehouseAccessRepository: WarehouseAccessRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private val principal: String = UUID.randomUUID().toString()

    @BeforeEach
    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            databaseClient
                .sql("DELETE FROM warehouse_access")
                .fetch()
                .rowsUpdated()
                .awaitFirst()
        }

    private fun send(
        key: String,
        value: String,
    ) {
        kafkaTemplate.send(TOPIC, key, value).get()
    }

    private fun changed(
        principalId: String,
        warehouseIds: String,
    ): String =
        """{"eventId":"${UUID.randomUUID()}","eventType":"WAREHOUSE_ACCESS_CHANGED","eventVersion":1,""" +
            """"occurredAt":"2026-10-12T10:00:00+09:00","source":"wms","data":{"principalId":"$principalId","warehouseIds":$warehouseIds}}"""

    /** 평면 형식(envelope 이전). 이제 받지 않는다 */
    private fun flat(
        principalId: String,
        warehouseIds: String,
    ): String =
        """{"eventType":"WAREHOUSE_ACCESS_CHANGED","eventId":"${UUID.randomUUID()}","occurredAt":"2026-10-12T10:00:00+09:00",""" +
            """"principalId":"$principalId","warehouseIds":$warehouseIds}"""

    /** 사본이 [expected]가 될 때까지 최대 [timeoutSeconds]초 기다린다 */
    private suspend fun awaitWarehouses(
        principalId: String,
        expected: Set<Long>,
        timeoutSeconds: Long = 30,
    ): Set<Long> {
        val deadline: Long = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos()
        var actual: Set<Long> = warehouseAccessRepository.findWarehouseIds(PrincipalId.of(principalId))
        while (actual != expected && System.nanoTime() < deadline) {
            delay(200)
            actual = warehouseAccessRepository.findWarehouseIds(PrincipalId.of(principalId))
        }
        return actual
    }

    @Nested
    inner class `소속 교체` {
        @Test
        fun `이벤트를 받으면 직원의 소속이 목록으로 채워진다`() =
            runBlocking<Unit> {
                send(principal, changed(principal, "[10, 20]"))

                assertThat(awaitWarehouses(principal, setOf(10L, 20L))).containsExactlyInAnyOrder(10L, 20L)
            }

        @Test
        fun `새 이벤트는 소속을 통째로 교체하고 빈 목록은 소속을 모두 지운다`() =
            runBlocking<Unit> {
                send(principal, changed(principal, "[10, 20]"))
                assertThat(awaitWarehouses(principal, setOf(10L, 20L))).hasSize(2)

                send(principal, changed(principal, "[20, 30]"))
                assertThat(awaitWarehouses(principal, setOf(20L, 30L))).containsExactlyInAnyOrder(20L, 30L)

                send(principal, changed(principal, "[]"))
                assertThat(awaitWarehouses(principal, emptySet())).isEmpty()
            }

        @Test
        fun `같은 이벤트가 중복 전달되어도 결과가 같고 다른 직원은 영향이 없다`() =
            runBlocking<Unit> {
                val other: String = UUID.randomUUID().toString()
                send(other, changed(other, "[40]"))
                assertThat(awaitWarehouses(other, setOf(40L))).containsExactly(40L)

                val event: String = changed(principal, "[10]")
                send(principal, event)
                send(principal, event)

                assertThat(awaitWarehouses(principal, setOf(10L))).containsExactly(10L)
                assertThat(warehouseAccessRepository.findWarehouseIds(PrincipalId.of(other))).containsExactly(40L)
            }
    }

    @Nested
    inner class `잘못된 메시지` {
        @Test
        fun `JSON이 아니거나 유형이나 필드가 잘못된 메시지는 DLT로 보내고 다음 메시지를 계속 처리한다`() =
            runBlocking<Unit> {
                send("bad-1", "not json")
                send(principal, changed(principal, "[10]").replace("WAREHOUSE_ACCESS_CHANGED", "SOMETHING_ELSE"))
                send("bad-3", changed("not-a-uuid", "[10]"))
                send(principal, changed(principal, "[10, 10]"))
                send(principal, flat(principal, "[10]"))
                send(principal, changed(principal, "[10]").replace(""""eventVersion":1""", """"eventVersion":2"""))
                send(principal, changed(principal, "[10]").replace(""""source":"wms"""", """"source":"oms""""))
                send(principal, changed(principal, "[50]"))

                assertThat(awaitWarehouses(principal, setOf(50L))).containsExactly(50L)
                val dead: List<ConsumerRecord<String, String>> = KafkaTestContainer.consume(listOf("$TOPIC.dlt"), 7)
                assertThat(dead.map { it.value() }).hasSize(7)
                assertThat(dead.map { it.value() }).anyMatch { it == "not json" }
                assertThat(dead.map { it.value() }).anyMatch { it.contains("SOMETHING_ELSE") }
                assertThat(dead.map { it.value() }).anyMatch { it.contains("not-a-uuid") }
                assertThat(dead.map { it.value() }).anyMatch { it.contains("[10, 10]") }
                assertThat(dead.map { it.value() }).anyMatch { it.startsWith("""{"eventType":"WAREHOUSE_ACCESS_CHANGED","eventId":""") }
                assertThat(dead.map { it.value() }).anyMatch { it.contains(""""eventVersion":2""") }
                assertThat(dead.map { it.value() }).anyMatch { it.contains(""""source":"oms"""") }
            }
    }
}
