package com.dozycoffee.ims.inventory.adapter.out.persistence

import com.dozycoffee.ims.global.config.ClockConfig
import com.dozycoffee.ims.global.config.R2dbcConfig
import com.dozycoffee.ims.global.security.LocalActorProvider
import com.dozycoffee.ims.inventory.domain.enumeration.LotStatus
import com.dozycoffee.ims.inventory.domain.exception.DuplicateLotException
import com.dozycoffee.ims.inventory.domain.model.Lot
import com.dozycoffee.ims.inventory.fixture.InventoryDbFixture
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
import java.time.LocalDate

@DataR2dbcTest
@ActiveProfiles("local")
@Import(LotPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class LotPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: LotPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    private lateinit var fixture: InventoryDbFixture
    private var productId: Long = 0L
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            fixture = InventoryDbFixture(databaseClient, adapter)
            productId = fixture.seedProduct()
        }

    @AfterEach
    fun cleanUp() = runBlocking<Unit> { fixture.cleanUp() }

    private fun newLot(
        lotNumber: String = "LOT-A",
        expirationDate: LocalDate? = today.plusDays(200),
    ): Lot = Lot.create(productId, lotNumber, today.minusDays(10), expirationDate, today, 30)

    private suspend fun auditColumns(lotId: Long): Map<String, Any?> =
        databaseClient
            .sql("SELECT created_at, created_by, updated_at, updated_by FROM lot WHERE lot_id = :id")
            .bind("id", lotId)
            .fetch()
            .one()
            .awaitFirst()

    @Nested
    inner class `저장과 조회` {
        @Test
        fun `저장하면 식별자가 부여되고 같은 값으로 조회된다`() =
            runBlocking<Unit> {
                val saved: Lot = adapter.save(newLot())

                val found: Lot? = adapter.findById(checkNotNull(saved.lotId))
                assertThat(saved.lotId).isNotNull()
                assertThat(found).isEqualTo(saved)
                assertThat(found?.productId).isEqualTo(productId)
                assertThat(found?.lotNumber).isEqualTo("LOT-A")
                assertThat(found?.manufactureDate).isEqualTo(today.minusDays(10))
                assertThat(found?.expirationDate).isEqualTo(today.plusDays(200))
                assertThat(found?.lotStatus).isEqualTo(LotStatus.NORMAL)
            }

        @Test
        fun `제조일자와 유통기한이 없는 Lot도 저장된다`() =
            runBlocking<Unit> {
                val saved: Lot = adapter.save(Lot.create(productId, "LOT-N", null, null, today, 30))

                val found: Lot = checkNotNull(adapter.findById(checkNotNull(saved.lotId)))
                assertThat(found.manufactureDate).isNull()
                assertThat(found.expirationDate).isNull()
            }

        @Test
        fun `상품과 Lot 번호로 조회하고 없으면 null이다`() =
            runBlocking<Unit> {
                adapter.save(newLot())

                assertThat(adapter.findByProductIdAndLotNumber(productId, "LOT-A")).isNotNull()
                assertThat(adapter.findByProductIdAndLotNumber(productId, "NONE")).isNull()
                assertThat(adapter.findByProductIdAndLotNumber(999_999L, "LOT-A")).isNull()
                assertThat(adapter.findById(999_999L)).isNull()
            }
    }

    @Nested
    inner class `Lot 번호` {
        @Test
        fun `같은 상품의 같은 Lot 번호는 중복으로 변환된다`() =
            runBlocking<Unit> {
                adapter.save(newLot())

                assertThrows<DuplicateLotException> { adapter.save(newLot()) }
            }

        @Test
        fun `대소문자가 다르면 다른 Lot이다`() =
            runBlocking<Unit> {
                val upper: Lot = adapter.save(newLot("LOT-A"))
                val lower: Lot = adapter.save(newLot("lot-a"))

                assertThat(upper.lotId).isNotEqualTo(lower.lotId)
                assertThat(adapter.findByProductIdAndLotNumber(productId, "lot-a")?.lotId).isEqualTo(lower.lotId)
                assertThat(adapter.findByProductIdAndLotNumber(productId, "LOT-A")?.lotId).isEqualTo(upper.lotId)
                assertThat(adapter.findByProductIdAndLotNumber(productId, "Lot-A")).isNull()
            }

        @Test
        fun `끝 공백만 다른 번호는 같은 Lot으로 취급한다(utf8mb4_bin은 PAD SPACE 비교)`() =
            runBlocking<Unit> {
                val saved: Lot = adapter.save(newLot("LOT-A"))

                assertThrows<DuplicateLotException> { adapter.save(newLot("LOT-A ")) }
                assertThat(adapter.findByProductIdAndLotNumber(productId, "LOT-A ")?.lotId).isEqualTo(saved.lotId)
            }

        @Test
        fun `다른 상품이면 같은 Lot 번호를 쓸 수 있다`() =
            runBlocking<Unit> {
                val otherProduct: Long = fixture.seedProduct("BEAN-002")
                adapter.save(newLot())

                val saved: Lot = adapter.save(Lot.create(otherProduct, "LOT-A", null, null, today, 30))

                assertThat(saved.lotId).isNotNull()
            }
    }

    @Nested
    inner class `갱신` {
        @Test
        fun `상태를 바꿔 저장하면 상태만 바뀌고 생성 정보는 유지된다`() =
            runBlocking<Unit> {
                val saved: Lot = adapter.save(newLot(expirationDate = today.plusDays(40)))
                val lotId: Long = checkNotNull(saved.lotId)
                val before: Map<String, Any?> = auditColumns(lotId)

                val loaded: Lot = checkNotNull(adapter.findById(lotId))
                loaded.refreshStatus(today.plusDays(15), 30)
                adapter.save(loaded)

                val after: Map<String, Any?> = auditColumns(lotId)
                assertThat(adapter.findById(lotId)?.lotStatus).isEqualTo(LotStatus.EXPIRING_SOON)
                assertThat(after["created_at"]).isEqualTo(before["created_at"])
                assertThat(after["created_by"]).isEqualTo(before["created_by"])
                assertThat(after["updated_by"]).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
            }
    }

    @Nested
    inner class `스키마` {
        @Test
        fun `LotStatus와 DB CHECK 제약의 값이 일치한다`() =
            runBlocking<Unit> {
                val clause: String =
                    databaseClient
                        .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'ck_lot_status'")
                        .fetch()
                        .one()
                        .awaitFirst()["CHECK_CLAUSE"] as String
                val values: List<String> = Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()

                assertThat(values).containsExactlyInAnyOrderElementsOf(LotStatus.entries.map { it.name })
            }

        @Test
        fun `모든 Lot 상태로 저장할 수 있다`() =
            runBlocking<Unit> {
                val expired: Lot = adapter.save(newLot("L-EXPIRED", today.minusDays(1)).also { })
                val soon: Lot = adapter.save(newLot("L-SOON", today.plusDays(5)))
                val normal: Lot = adapter.save(newLot("L-NORMAL", today.plusDays(90)))

                assertThat(listOf(expired, soon, normal).map { adapter.findById(checkNotNull(it.lotId))?.lotStatus })
                    .containsExactlyInAnyOrderElementsOf(LotStatus.entries)
            }
    }
}
