package com.dozycoffee.ims.inventory.domain.model

import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.inventory.domain.enumeration.LotStatus
import com.dozycoffee.ims.inventory.domain.exception.InventoryErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class LotModelTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 7)

    private fun create(
        lotNumber: String? = "LOT-2026-A",
        manufactureDate: LocalDate? = today.minusDays(10),
        expirationDate: LocalDate? = today.plusDays(200),
    ): Lot = Lot.create(100L, lotNumber, manufactureDate, expirationDate, today, 30)

    @Test
    fun `Lot 번호는 공급사 값 그대로 보존한다`() {
        assertEquals(" lot-a ", create(lotNumber = " lot-a ").lotNumber)
        assertNotEquals(create(lotNumber = "lot-a").lotNumber, create(lotNumber = "LOT-A").lotNumber)
    }

    @Test
    fun `Lot 번호가 비어 있으면 생성 실패`() {
        listOf(null, "", "  ").forEach { number: String? ->
            val e: InvalidDomainValueException = assertThrows { create(lotNumber = number) }
            assertEquals(InventoryErrorCode.INVALID_LOT_NUMBER, e.errorCode)
        }
    }

    @Test
    fun `유통기한이 제조일자보다 빠르면 생성 실패하고 같은 날은 허용한다`() {
        val e: InvalidDomainValueException =
            assertThrows { create(manufactureDate = today, expirationDate = today.minusDays(1)) }
        assertEquals(InventoryErrorCode.INVALID_LOT_DATES, e.errorCode)

        create(manufactureDate = today.plusDays(1), expirationDate = today.plusDays(1))
    }

    @Test
    fun `제조일자나 유통기한이 없는 Lot도 만들 수 있다`() {
        assertNull(create(manufactureDate = null).manufactureDate)
        assertNull(create(expirationDate = null).expirationDate)
        assertNull(create(manufactureDate = null, expirationDate = null).lotId)
    }

    @Test
    fun `유통기한 기준으로 상태를 판정한다`() {
        assertEquals(LotStatus.NORMAL, create(expirationDate = today.plusDays(31)).lotStatus)
        assertEquals(LotStatus.EXPIRING_SOON, create(expirationDate = today.plusDays(30)).lotStatus)
        assertEquals(LotStatus.EXPIRING_SOON, create(expirationDate = today.plusDays(1)).lotStatus)
        assertEquals(LotStatus.EXPIRED, create(manufactureDate = null, expirationDate = today).lotStatus)
        assertEquals(LotStatus.EXPIRED, create(manufactureDate = null, expirationDate = today.minusDays(1)).lotStatus)
    }

    @Test
    fun `유통기한이 없으면 항상 정상이다`() {
        val lot: Lot = create(expirationDate = null)

        assertEquals(LotStatus.NORMAL, lot.lotStatus)
        assertEquals(LotStatus.NORMAL, lot.statusAt(today.plusYears(10), 30))
    }

    @Test
    fun `임박 기준 일수가 0이면 당일 경과 전까지 정상이다`() {
        val lot: Lot = create(expirationDate = today.plusDays(1))

        assertEquals(LotStatus.NORMAL, lot.statusAt(today, 0))
        assertEquals(LotStatus.EXPIRED, lot.statusAt(today.plusDays(1), 0))
    }

    @Test
    fun `임박 기준 일수가 음수이면 실패`() {
        val e: InvalidDomainValueException = assertThrows { create().statusAt(today, -1) }
        assertEquals(InventoryErrorCode.INVALID_EXPIRING_SOON_DAYS, e.errorCode)
    }

    @Test
    fun `날짜가 지나면 상태를 갱신하고 바뀌었을 때만 true를 반환한다`() {
        val lot: Lot = create(expirationDate = today.plusDays(40))

        assertFalse(lot.refreshStatus(today.plusDays(5), 30))
        assertTrue(lot.refreshStatus(today.plusDays(10), 30))
        assertEquals(LotStatus.EXPIRING_SOON, lot.lotStatus)
        assertFalse(lot.refreshStatus(today.plusDays(11), 30))
        assertTrue(lot.refreshStatus(today.plusDays(40), 30))
        assertEquals(LotStatus.EXPIRED, lot.lotStatus)
    }

    @Test
    fun `복원한 Lot은 저장된 값을 그대로 가지고 식별자로 동등성을 판단한다`() {
        val a: Lot = Lot.reconstitute(1L, 100L, "A", null, today, LotStatus.EXPIRED)
        val b: Lot = Lot.reconstitute(1L, 200L, "B", null, null, LotStatus.NORMAL)
        val c: Lot = Lot.reconstitute(2L, 100L, "A", null, today, LotStatus.EXPIRED)

        assertEquals(LotStatus.EXPIRED, a.lotStatus)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Nested
    inner class `날짜 비교` {
        @Test
        fun `제조일자와 유통기한이 모두 같을 때만 같다`() {
            val lot: Lot = create()

            assertTrue(lot.hasSameDates(today.minusDays(10), today.plusDays(200)))
            assertFalse(lot.hasSameDates(today.minusDays(11), today.plusDays(200)))
            assertFalse(lot.hasSameDates(today.minusDays(10), today.plusDays(201)))
        }

        @Test
        fun `한쪽이 없는 것과 있는 것은 다르고 둘 다 없으면 같다`() {
            assertFalse(create().hasSameDates(null, today.plusDays(200)))
            assertFalse(create(expirationDate = null).hasSameDates(today.minusDays(10), today.plusDays(200)))
            assertTrue(create(manufactureDate = null, expirationDate = null).hasSameDates(null, null))
        }
    }
}
