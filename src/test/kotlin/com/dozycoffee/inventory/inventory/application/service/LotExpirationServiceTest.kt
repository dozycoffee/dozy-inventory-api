package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotExpirationResult
import com.dozycoffee.inventory.inventory.application.port.out.InventoryHistoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.HistoryType
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.enumeration.ReferenceType
import com.dozycoffee.inventory.inventory.domain.exception.DuplicateIdempotencyKeyException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Inventory
import com.dozycoffee.inventory.inventory.domain.model.InventoryHistory
import com.dozycoffee.inventory.inventory.domain.model.Lot
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class LotExpirationServiceTest {
    @Mock
    private lateinit var lotRepository: LotRepository

    @Mock
    private lateinit var inventoryRepository: InventoryRepository

    @Mock
    private lateinit var inventoryHistoryRepository: InventoryHistoryRepository

    private lateinit var service: LotExpirationService

    private val today: LocalDate = LocalDate.of(2026, 10, 11)

    @BeforeEach
    fun setUp() {
        service =
            LotExpirationService(
                lotRepository,
                inventoryRepository,
                inventoryHistoryRepository,
                DirectTransactionalOperator(),
                Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                30,
            )
    }

    private fun lot(
        expiration: LocalDate?,
        status: LotStatus,
    ): Lot = Lot.reconstitute(7L, 100L, "LOT-A", null, expiration, status)

    private fun row(
        id: Long,
        warehouseId: Long,
        quantity: Int,
        reserved: Int = 0,
        quality: QualityStatus = QualityStatus.NORMAL,
    ): Inventory = Inventory.reconstitute(id, warehouseId, 100L, 7L, quality, quantity, reserved, false, null, null)

    @Nested
    inner class `후보 조회` {
        @Test
        fun `오늘과 임박 기준일을 계산해 저장소에 넘긴다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findScanCandidateIds(today, today.plusDays(30), 100)).thenReturn(listOf(3L, 5L))

                assertEquals(listOf(3L, 5L), service.findLotIds(100))
            }

        @Test
        fun `개수는 1 이상 1000 이하여야 한다`() =
            runBlocking<Unit> {
                assertThrows<IllegalArgumentException> { service.findLotIds(0) }
                assertThrows<IllegalArgumentException> { service.findLotIds(1001) }
            }
    }

    @Nested
    inner class `상태 갱신` {
        @Test
        fun `임박 기준에 들어온 Lot은 상태만 바꾸고 전환하지 않는다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(7L)).thenReturn(lot(today.plusDays(10), LotStatus.NORMAL))
                whenever(lotRepository.updateStatus(any(), eq(LotStatus.NORMAL))).thenReturn(true)

                val result: LotExpirationResult = service.process(7L)

                assertTrue(result.statusChanged)
                assertFalse(result.becameExpired)
                assertFalse(result.expired)
                assertTrue(result.transfers.isEmpty())
                verifyBlocking(inventoryRepository, never()) { findAllByLot(any(), any()) }
            }

        @Test
        fun `상태가 이미 맞는 Lot은 갱신하지 않는다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(7L)).thenReturn(lot(today.plusDays(10), LotStatus.EXPIRING_SOON))

                val result: LotExpirationResult = service.process(7L)

                assertFalse(result.statusChanged)
                verifyBlocking(lotRepository, never()) { updateStatus(any(), any()) }
            }

        @Test
        fun `없는 Lot이면 거부한다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(7L)).thenReturn(null)

                assertThrows<LotNotFoundException> { service.process(7L) }
            }

        @Test
        fun `다른 인스턴스가 먼저 상태를 바꿨으면 변화 없는 결과를 반환하고 전환하지 않는다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(7L)).thenReturn(lot(today.minusDays(1), LotStatus.NORMAL))
                whenever(lotRepository.updateStatus(any(), eq(LotStatus.NORMAL))).thenReturn(false)

                val result: LotExpirationResult = service.process(7L)

                assertFalse(result.statusChanged)
                assertFalse(result.expired)
                verifyBlocking(inventoryRepository, never()) { findAllByLot(any(), any()) }
            }
    }

    @Nested
    inner class `품질 전환` {
        private suspend fun stubExpiredLot(status: LotStatus = LotStatus.EXPIRING_SOON) {
            whenever(lotRepository.findById(7L)).thenReturn(lot(today, status))
            if (status != LotStatus.EXPIRED) whenever(lotRepository.updateStatus(any(), eq(status))).thenReturn(true)
            whenever(inventoryHistoryRepository.save(any())).thenAnswer { it.getArgument<InventoryHistory>(0) }
        }

        @Test
        fun `경과로 바뀐 Lot의 정상 품질 행은 가용 수량만 폐기 예정 행으로 옮긴다`() =
            runBlocking<Unit> {
                stubExpiredLot()
                whenever(
                    inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL),
                ).thenReturn(listOf(row(5L, 10L, quantity = 100, reserved = 30)))
                whenever(inventoryRepository.decrease(5L, 70)).thenReturn(row(5L, 10L, quantity = 30, reserved = 30))
                whenever(inventoryRepository.increase(InventoryKey(10L, 7L, QualityStatus.DISPOSAL_SCHEDULED), 100L, 70))
                    .thenReturn(row(8L, 10L, quantity = 70, quality = QualityStatus.DISPOSAL_SCHEDULED))

                val result: LotExpirationResult = service.process(7L)

                assertTrue(result.becameExpired)
                assertTrue(result.expired)
                assertEquals(listOf(LotExpirationResult.Transfer(10L, 5L, 8L, 70, 30)), result.transfers)
            }

        @Test
        fun `전환 전 행에 마이너스 전환 후 행에 플러스의 QUALITY_TRANSFER 이력을 같은 키로 남긴다`() =
            runBlocking<Unit> {
                stubExpiredLot()
                whenever(inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL)).thenReturn(listOf(row(5L, 10L, quantity = 40)))
                whenever(inventoryRepository.decrease(5L, 40)).thenReturn(row(5L, 10L, quantity = 0))
                whenever(inventoryRepository.increase(any(), eq(100L), eq(40)))
                    .thenReturn(row(8L, 10L, quantity = 45, quality = QualityStatus.DISPOSAL_SCHEDULED))

                service.process(7L)

                val histories = argumentCaptor<InventoryHistory>()
                verifyBlocking(inventoryHistoryRepository, org.mockito.kotlin.times(2)) { save(histories.capture()) }
                assertEquals(listOf(5L, 8L), histories.allValues.map { it.inventoryId })
                assertEquals(listOf(-40, 40), histories.allValues.map { it.quantityChange })
                assertEquals(listOf(0, 45), histories.allValues.map { it.quantityAfter })
                assertTrue(histories.allValues.all { it.historyType == HistoryType.QUALITY_TRANSFER })
                assertTrue(histories.allValues.all { it.referenceType == ReferenceType.LOT_EXPIRATION && it.referenceId == 7L })
                assertTrue(histories.allValues.all { it.idempotencyKey == IdempotencyKey.of("lot-expiration-7-2026-10-11") })
                assertTrue(histories.allValues.all { it.requesterService == RequesterService.of("scheduler") })
            }

        @Test
        fun `행은 ID 오름차순으로 옮기고 가용 수량이 없는 행은 건드리지 않는다`() =
            runBlocking<Unit> {
                stubExpiredLot()
                whenever(inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL))
                    .thenReturn(listOf(row(3L, 10L, quantity = 10), row(4L, 20L, quantity = 5, reserved = 5), row(9L, 30L, quantity = 7)))
                whenever(inventoryRepository.decrease(any(), any())).thenAnswer { row(it.getArgument(0), 0L, quantity = 0) }
                whenever(inventoryRepository.increase(any(), eq(100L), any())).thenAnswer {
                    row(
                        100L + it.getArgument<InventoryKey>(0).warehouseId,
                        it.getArgument<InventoryKey>(0).warehouseId,
                        quantity = 1,
                        quality = QualityStatus.DISPOSAL_SCHEDULED,
                    )
                }

                val result: LotExpirationResult = service.process(7L)

                assertEquals(listOf(3L, 9L), result.transfers.map { it.sourceInventoryId })
                inOrder(inventoryRepository) {
                    verify(inventoryRepository).decrease(3L, 10)
                    verify(inventoryRepository).decrease(9L, 7)
                }
                verifyBlocking(inventoryRepository, never()) { decrease(eq(4L), any()) }
            }

        @Test
        fun `그 사이 예약이 늘어 가용 수량이 모자라면 그 행은 건너뛰고 나머지를 옮긴다`() =
            runBlocking<Unit> {
                stubExpiredLot()
                whenever(inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL))
                    .thenReturn(listOf(row(3L, 10L, quantity = 10), row(9L, 30L, quantity = 7)))
                whenever(inventoryRepository.decrease(3L, 10)).thenThrow(InsufficientAvailableQuantityException())
                whenever(inventoryRepository.decrease(9L, 7)).thenReturn(row(9L, 30L, quantity = 0))
                whenever(inventoryRepository.increase(any(), eq(100L), eq(7)))
                    .thenReturn(row(20L, 30L, quantity = 7, quality = QualityStatus.DISPOSAL_SCHEDULED))

                val result: LotExpirationResult = service.process(7L)

                assertEquals(listOf(9L), result.transfers.map { it.sourceInventoryId })
            }

        @Test
        fun `이미 경과한 Lot도 남은 가용 수량을 옮기지만 경과로 바뀐 것은 아니다`() =
            runBlocking<Unit> {
                stubExpiredLot(LotStatus.EXPIRED)
                whenever(
                    inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL),
                ).thenReturn(listOf(row(5L, 10L, quantity = 30, reserved = 0)))
                whenever(inventoryRepository.decrease(5L, 30)).thenReturn(row(5L, 10L, quantity = 0))
                whenever(inventoryRepository.increase(any(), eq(100L), eq(30)))
                    .thenReturn(row(8L, 10L, quantity = 30, quality = QualityStatus.DISPOSAL_SCHEDULED))

                val result: LotExpirationResult = service.process(7L)

                assertFalse(result.statusChanged)
                assertFalse(result.becameExpired)
                assertTrue(result.expired)
                assertEquals(1, result.transfers.size)
                verifyBlocking(lotRepository, never()) { updateStatus(any(), any()) }
            }

        @Test
        fun `같은 날 같은 Lot을 이미 처리했으면 중복 키를 변화 없는 결과로 바꾼다`() =
            runBlocking<Unit> {
                whenever(lotRepository.findById(7L)).thenReturn(lot(today, LotStatus.EXPIRED))
                whenever(inventoryRepository.findAllByLot(7L, QualityStatus.NORMAL)).thenReturn(listOf(row(5L, 10L, quantity = 30)))
                whenever(inventoryRepository.decrease(5L, 30)).thenReturn(row(5L, 10L, quantity = 0))
                whenever(inventoryRepository.increase(any(), eq(100L), eq(30)))
                    .thenReturn(row(8L, 10L, quantity = 30, quality = QualityStatus.DISPOSAL_SCHEDULED))
                whenever(inventoryHistoryRepository.save(any())).thenThrow(DuplicateIdempotencyKeyException())

                val result: LotExpirationResult = service.process(7L)

                assertTrue(result.expired)
                assertTrue(result.transfers.isEmpty())
                assertFalse(result.statusChanged)
            }
    }
}
