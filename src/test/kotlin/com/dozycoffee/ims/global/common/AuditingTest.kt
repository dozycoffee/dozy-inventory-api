package com.dozycoffee.ims.global.common

import com.dozycoffee.ims.global.security.ActorContext
import com.dozycoffee.ims.global.security.LocalActorProvider
import com.dozycoffee.ims.global.security.SystemActor
import com.dozycoffee.ims.support.ImsIntegrationTest
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Table("product")
class AuditedProduct(
    @Id @Column("product_id") var id: Long? = null,
    var productCode: String,
    var productName: String,
    var category: String = "BEAN",
    var unit: String = "KG",
    var productStatus: String = "ACTIVE",
) : BaseEntity()

class MutableClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}

@ImsIntegrationTest
@Import(AuditingTest.TestClockConfig::class)
class AuditingTest {
    @TestConfiguration
    class TestClockConfig {
        @Bean
        @Primary
        fun testClock(): MutableClock = MutableClock(Instant.parse("2026-10-06T00:00:00Z"))
    }

    @Autowired
    private lateinit var template: R2dbcEntityTemplate

    @Autowired
    private lateinit var clock: MutableClock

    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            template.delete(AuditedProduct::class.java).all().awaitSingle()
            Unit
        }

    @Test
    fun `저장하면 생성·수정 시각은 Clock 기준으로 작성자는 현재 Actor로 기록된다`() =
        runBlocking<Unit> {
            clock.current = Instant.parse("2026-10-06T00:00:00Z")

            val saved: AuditedProduct = template.insert(AuditedProduct(productCode = "A-1", productName = "원두")).awaitSingle()

            val expected: LocalDateTime = LocalDateTime.of(2026, 10, 6, 0, 0)
            assertThat(saved.createdAt).isEqualTo(expected)
            assertThat(saved.updatedAt).isEqualTo(expected)
            assertThat(saved.createdBy).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            assertThat(saved.updatedBy).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
        }

    @Test
    fun `요청 밖 작업을 SystemActor로 실행하면 작성자는 system으로 기록된다`() =
        runBlocking<Unit> {
            val saved: AuditedProduct =
                ActorContext.with(SystemActor) {
                    template.insert(AuditedProduct(productCode = "A-2", productName = "시럽")).awaitSingle()
                }

            assertThat(saved.createdBy).isEqualTo("system")
            assertThat(saved.updatedBy).isEqualTo("system")
        }

    @Test
    fun `갱신하면 수정 시각과 수정자만 바뀌고 생성 정보는 유지된다`() =
        runBlocking<Unit> {
            clock.current = Instant.parse("2026-10-06T00:00:00Z")
            val saved: AuditedProduct = template.insert(AuditedProduct(productCode = "A-3", productName = "원두")).awaitSingle()

            clock.current = Instant.parse("2026-10-07T09:30:00Z")
            val loaded: AuditedProduct = find(saved.id!!)
            loaded.productName = "원두(변경)"
            val updated: AuditedProduct =
                ActorContext.with(SystemActor) { template.update(loaded).awaitSingle() }

            assertThat(updated.createdAt).isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0))
            assertThat(updated.createdBy).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            assertThat(updated.updatedAt).isEqualTo(LocalDateTime.of(2026, 10, 7, 9, 30))
            assertThat(updated.updatedBy).isEqualTo("system")
            assertThat(find(saved.id!!).productName).isEqualTo("원두(변경)")
        }

    @Test
    fun `도메인 모델에서 재구성한 엔티티는 copyAuditFieldsFrom으로 생성 정보를 보존한 뒤 갱신한다`() =
        runBlocking<Unit> {
            val saved: AuditedProduct = template.insert(AuditedProduct(productCode = "A-4", productName = "원두")).awaitSingle()

            val rebuilt: AuditedProduct = AuditedProduct(id = saved.id, productCode = "A-4", productName = "재구성")
            rebuilt.copyAuditFieldsFrom(find(saved.id!!))
            template.update(rebuilt).awaitSingle()

            val reloaded: AuditedProduct = find(saved.id!!)
            assertThat(reloaded.productName).isEqualTo("재구성")
            assertThat(reloaded.createdAt).isEqualTo(saved.createdAt)
            assertThat(reloaded.createdBy).isEqualTo(saved.createdBy)
        }

    @Test
    fun `생성 정보를 복사하지 않고 재구성한 엔티티를 갱신하면 NOT NULL 위반으로 거부된다`() =
        runBlocking<Unit> {
            val saved: AuditedProduct = template.insert(AuditedProduct(productCode = "A-5", productName = "원두")).awaitSingle()
            val rebuilt: AuditedProduct = AuditedProduct(id = saved.id, productCode = "A-5", productName = "복사 누락")

            assertThrows<DataIntegrityViolationException> { template.update(rebuilt).awaitSingle() }
            Unit
        }

    private suspend fun find(id: Long): AuditedProduct =
        template
            .select(AuditedProduct::class.java)
            .matching(Query.query(Criteria.where("product_id").`is`(id)))
            .one()
            .awaitSingle()
}
