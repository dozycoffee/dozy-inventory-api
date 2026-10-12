package com.dozycoffee.inventory.reservation.adapter.`in`.scheduler

import com.dozycoffee.inventory.reservation.application.port.`in`.ScanLotExpirationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.result.LotExpirationScanResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class LotExpirationScanSchedulerTest {
    @Mock
    private lateinit var scanLotExpirationUseCase: ScanLotExpirationUseCase

    private lateinit var scheduler: LotExpirationScanScheduler

    @BeforeEach
    fun setUp() {
        scheduler = LotExpirationScanScheduler(scanLotExpirationUseCase, 3)
    }

    @Test
    fun `묶음이 가득 차지 않으면 한 번만 처리하고 끝낸다`() =
        runBlocking<Unit> {
            whenever(scanLotExpirationUseCase.scan(3)).thenReturn(LotExpirationScanResult(2, 2, 0))

            scheduler.scan()

            verifyBlocking(scanLotExpirationUseCase, times(1)) { scan(3) }
        }

    @Test
    fun `묶음이 가득 차고 성공한 Lot이 있으면 이어서 다음 묶음을 처리한다`() =
        runBlocking<Unit> {
            whenever(scanLotExpirationUseCase.scan(3))
                .thenReturn(LotExpirationScanResult(3, 3, 1))
                .thenReturn(LotExpirationScanResult(3, 1, 0))
                .thenReturn(LotExpirationScanResult(1, 1, 0))

            scheduler.scan()

            verifyBlocking(scanLotExpirationUseCase, times(3)) { scan(3) }
        }

    @Test
    fun `성공한 Lot이 하나도 없으면 같은 묶음을 반복하지 않고 멈춘다`() =
        runBlocking<Unit> {
            whenever(scanLotExpirationUseCase.scan(3)).thenReturn(LotExpirationScanResult(3, 0, 0))

            scheduler.scan()

            verifyBlocking(scanLotExpirationUseCase, times(1)) { scan(3) }
        }

    @Test
    fun `한 번에 최대 10묶음까지만 처리한다`() =
        runBlocking<Unit> {
            whenever(scanLotExpirationUseCase.scan(3)).thenReturn(LotExpirationScanResult(3, 3, 0))

            scheduler.scan()

            verifyBlocking(scanLotExpirationUseCase, times(10)) { scan(3) }
        }

    @Test
    fun `스캔이 실패해도 예외를 밖으로 던지지 않는다`() =
        runBlocking<Unit> {
            whenever(scanLotExpirationUseCase.scan(3)).thenThrow(IllegalStateException("db down"))

            scheduler.scan()

            verifyBlocking(scanLotExpirationUseCase, times(1)) { scan(3) }
        }
}
