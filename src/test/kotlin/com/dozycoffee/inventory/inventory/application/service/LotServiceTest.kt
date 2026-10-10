package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.result.LotResult
import com.dozycoffee.inventory.inventory.application.port.out.LotRepository
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.LotNotFoundException
import com.dozycoffee.inventory.inventory.domain.model.Lot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class LotServiceTest {
    @Mock
    private lateinit var lotRepository: LotRepository

    private lateinit var service: LotService

    @BeforeEach
    fun setUp() {
        service = LotService(lotRepository)
    }

    @Test
    fun `상품과 Lot 번호로 Lot을 찾아 결과로 바꾼다`() =
        runBlocking<Unit> {
            whenever(lotRepository.findByProductIdAndLotNumber(100L, "LOT-A")).thenReturn(
                Lot.reconstitute(7L, 100L, "LOT-A", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 3, 1), LotStatus.NORMAL),
            )

            assertEquals(
                LotResult(7L, 100L, "LOT-A", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 3, 1)),
                service.getByProductAndLotNumber(100L, "LOT-A"),
            )
        }

    @Test
    fun `없는 Lot이면 거부한다`() =
        runBlocking<Unit> {
            whenever(lotRepository.findByProductIdAndLotNumber(100L, "NOPE")).thenReturn(null)

            assertThrows<LotNotFoundException> { service.getByProductAndLotNumber(100L, "NOPE") }
        }
}
