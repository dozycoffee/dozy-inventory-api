package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.FindExpiredLotInventoryIdsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.FindLotsToScanUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ProcessLotExpirationUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotExpirationResult
import com.dozycoffee.inventory.reservation.application.port.`in`.FindReservationsByInventoryUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ForceReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class LotExpirationScanServiceTest {
    @Mock
    private lateinit var findLotsToScanUseCase: FindLotsToScanUseCase

    @Mock
    private lateinit var findExpiredLotInventoryIdsUseCase: FindExpiredLotInventoryIdsUseCase

    @Mock
    private lateinit var processLotExpirationUseCase: ProcessLotExpirationUseCase

    @Mock
    private lateinit var findReservationsByInventoryUseCase: FindReservationsByInventoryUseCase

    @Mock
    private lateinit var forceReleaseReservationUseCase: ForceReleaseReservationUseCase

    private lateinit var service: LotExpirationScanService

    @BeforeEach
    fun setUp() {
        service =
            LotExpirationScanService(
                findLotsToScanUseCase,
                findExpiredLotInventoryIdsUseCase,
                processLotExpirationUseCase,
                findReservationsByInventoryUseCase,
                forceReleaseReservationUseCase,
            )
    }

    private fun processed(lotId: Long): LotExpirationResult =
        LotExpirationResult(lotId, 100L, "L-$lotId", null, true, true, true, emptyList())

    @Test
    fun `확정 전 예약을 강제 해제한 뒤 확정 예약을 조회하고 그 목록으로 Lot을 처리한다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(listOf(7L))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(7L)).thenReturn(listOf(5L, 6L))
            whenever(findReservationsByInventoryUseCase.findUnconfirmedIds(setOf(5L, 6L))).thenReturn(listOf(1L, 2L))
            whenever(forceReleaseReservationUseCase.forceRelease(any(), eq("LOT_EXPIRED"))).thenReturn(true)
            whenever(findReservationsByInventoryUseCase.findConfirmedIds(setOf(5L, 6L))).thenReturn(listOf(3L))
            whenever(processLotExpirationUseCase.process(7L, listOf(3L))).thenReturn(processed(7L))

            val result: LotExpirationScanResult = service.scan(10)

            assertEquals(LotExpirationScanResult(1, 1, 2), result)
            inOrder(forceReleaseReservationUseCase, findReservationsByInventoryUseCase, processLotExpirationUseCase) {
                verify(forceReleaseReservationUseCase).forceRelease(1L, "LOT_EXPIRED")
                verify(forceReleaseReservationUseCase).forceRelease(2L, "LOT_EXPIRED")
                verify(findReservationsByInventoryUseCase).findConfirmedIds(setOf(5L, 6L))
                verify(processLotExpirationUseCase).process(7L, listOf(3L))
            }
        }

    @Test
    fun `예약 수량이 있는 행이 없으면 예약을 조회하지 않고 Lot만 처리한다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(listOf(7L))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(7L)).thenReturn(emptyList())
            whenever(findReservationsByInventoryUseCase.findConfirmedIds(emptySet())).thenReturn(emptyList())
            whenever(processLotExpirationUseCase.process(7L, emptyList())).thenReturn(processed(7L))

            assertEquals(LotExpirationScanResult(1, 1, 0), service.scan(10))

            verifyBlocking(findReservationsByInventoryUseCase, never()) { findUnconfirmedIds(any()) }
            verifyBlocking(forceReleaseReservationUseCase, never()) { forceRelease(any(), any()) }
        }

    @Test
    fun `이미 처리된 예약은 강제 해제 수에 세지 않는다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(listOf(7L))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(7L)).thenReturn(listOf(5L))
            whenever(findReservationsByInventoryUseCase.findUnconfirmedIds(setOf(5L))).thenReturn(listOf(1L, 2L))
            whenever(forceReleaseReservationUseCase.forceRelease(1L, "LOT_EXPIRED")).thenReturn(false)
            whenever(forceReleaseReservationUseCase.forceRelease(2L, "LOT_EXPIRED")).thenReturn(true)
            whenever(findReservationsByInventoryUseCase.findConfirmedIds(setOf(5L))).thenReturn(emptyList())
            whenever(processLotExpirationUseCase.process(7L, emptyList())).thenReturn(processed(7L))

            assertEquals(1, service.scan(10).forceReleased)
        }

    @Test
    fun `예약 한 건의 강제 해제가 실패해도 나머지 예약과 Lot 처리를 계속한다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(listOf(7L))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(7L)).thenReturn(listOf(5L))
            whenever(findReservationsByInventoryUseCase.findUnconfirmedIds(setOf(5L))).thenReturn(listOf(1L, 2L))
            whenever(forceReleaseReservationUseCase.forceRelease(1L, "LOT_EXPIRED")).thenThrow(IllegalStateException("db down"))
            whenever(forceReleaseReservationUseCase.forceRelease(2L, "LOT_EXPIRED")).thenReturn(true)
            whenever(findReservationsByInventoryUseCase.findConfirmedIds(setOf(5L))).thenReturn(emptyList())
            whenever(processLotExpirationUseCase.process(7L, emptyList())).thenReturn(processed(7L))

            assertEquals(LotExpirationScanResult(1, 1, 1), service.scan(10))
        }

    @Test
    fun `Lot 한 건이 실패해도 나머지 Lot을 계속 처리하고 성공한 수만 센다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(listOf(7L, 8L))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(7L)).thenThrow(IllegalStateException("db down"))
            whenever(findExpiredLotInventoryIdsUseCase.findReservedInventoryIds(8L)).thenReturn(emptyList())
            whenever(findReservationsByInventoryUseCase.findConfirmedIds(emptySet())).thenReturn(emptyList())
            whenever(processLotExpirationUseCase.process(8L, emptyList())).thenReturn(processed(8L))

            assertEquals(LotExpirationScanResult(2, 1, 0), service.scan(10))
            verifyBlocking(processLotExpirationUseCase, never()) { process(eq(7L), any()) }
        }

    @Test
    fun `처리할 Lot이 없으면 아무것도 하지 않는다`() =
        runBlocking<Unit> {
            whenever(findLotsToScanUseCase.findLotIds(10)).thenReturn(emptyList())

            assertEquals(LotExpirationScanResult(0, 0, 0), service.scan(10))
        }
}
