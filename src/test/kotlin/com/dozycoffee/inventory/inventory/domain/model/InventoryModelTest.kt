package com.dozycoffee.inventory.inventory.domain.model

import com.dozycoffee.inventory.global.domain.QualityStatus
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.exception.AllocationHeldException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientReservedQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotReservableException
import com.dozycoffee.inventory.inventory.domain.valueobject.InventoryKey
import com.dozycoffee.inventory.inventory.fixture.InventoryTestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import kotlin.random.Random

class InventoryModelTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 7, 9, 0)

    private fun inventory(
        quantity: Int = 10,
        reserved: Int = 0,
        status: QualityStatus = QualityStatus.NORMAL,
    ): Inventory =
        InventoryTestBuilder()
            .quantity(quantity)
            .reservedQuantity(reserved)
            .qualityStatus(status)
            .build()

    private fun assertInvalidQuantity(block: () -> Unit) {
        val e: InvalidDomainValueException = assertThrows { block() }
        assertEquals(InventoryErrorCode.INVALID_QUANTITY, e.errorCode)
    }

    @Test
    fun `새 재고 행은 수량이 0이고 식별자와 보류가 없다`() {
        val inventory: Inventory = Inventory.create(10L, 100L, 1000L, QualityStatus.NORMAL)

        assertNull(inventory.inventoryId)
        assertEquals(0, inventory.quantity)
        assertEquals(0, inventory.reservedQuantity)
        assertEquals(0, inventory.availableQuantity)
        assertFalse(inventory.allocationHold)
        assertEquals(InventoryKey(10L, 1000L, QualityStatus.NORMAL), inventory.key)
    }

    @Test
    fun `가용 수량은 총 수량에서 예약 수량을 뺀 값이다`() {
        assertEquals(7, inventory(quantity = 10, reserved = 3).availableQuantity)
    }

    @Test
    fun `정상 품질이고 보류가 아닌 행만 할당할 수 있다`() {
        assertTrue(inventory().isAllocatable)
        assertFalse(inventory(status = QualityStatus.DEFECTIVE).isAllocatable)
        assertFalse(inventory(status = QualityStatus.DISPOSAL_SCHEDULED).isAllocatable)
        assertFalse(InventoryTestBuilder().held("부족 보고", now).build().isAllocatable)
    }

    @Test
    fun `수량을 늘리면 총 수량만 증가한다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 4)

        inventory.increase(5)

        assertEquals(15, inventory.quantity)
        assertEquals(4, inventory.reservedQuantity)
    }

    @Test
    fun `수량 증가는 1 이상이어야 하고 Int 범위를 넘으면 실패`() {
        assertInvalidQuantity { inventory().increase(0) }
        assertInvalidQuantity { inventory().increase(-1) }
        val e: InvalidDomainValueException = assertThrows { inventory(quantity = Int.MAX_VALUE).increase(1) }
        assertEquals(InventoryErrorCode.QUANTITY_OVERFLOW, e.errorCode)
        inventory(quantity = Int.MAX_VALUE - 1).increase(1)
    }

    @Test
    fun `가용 수량 안에서 수량을 줄이고 0까지 줄여도 행은 유지된다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 4)

        inventory.decrease(6)

        assertEquals(4, inventory.quantity)
        assertEquals(0, inventory.availableQuantity)
        val empty: Inventory = inventory(quantity = 3).also { it.decrease(3) }
        assertEquals(0, empty.quantity)
        assertNotEquals(null, empty.inventoryId)
    }

    @Test
    fun `예약된 수량까지 줄이려 하면 실패하고 값은 바뀌지 않는다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 4)

        assertThrows<InsufficientAvailableQuantityException> { inventory.decrease(7) }
        assertEquals(10, inventory.quantity)
        assertInvalidQuantity { inventory.decrease(0) }
    }

    @Test
    fun `가용 수량 안에서 예약하고 정확히 가용 수량만큼도 예약할 수 있다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 3)

        inventory.reserve(7)

        assertEquals(10, inventory.reservedQuantity)
        assertEquals(0, inventory.availableQuantity)
        assertEquals(10, inventory.quantity)
    }

    @Test
    fun `가용 수량을 넘는 예약은 실패하고 값은 바뀌지 않는다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 3)

        assertThrows<InsufficientAvailableQuantityException> { inventory.reserve(8) }
        assertEquals(3, inventory.reservedQuantity)
        assertInvalidQuantity { inventory.reserve(0) }
    }

    @Test
    fun `정상 품질이 아닌 재고는 예약할 수 없다`() {
        assertThrows<InventoryNotReservableException> { inventory(status = QualityStatus.DEFECTIVE).reserve(1) }
        assertThrows<InventoryNotReservableException> { inventory(status = QualityStatus.DISPOSAL_SCHEDULED).checkReservable(1) }
    }

    @Test
    fun `할당 보류 재고는 예약할 수 없다`() {
        assertThrows<AllocationHeldException> { InventoryTestBuilder().held("부족 보고", now).build().reserve(1) }
    }

    @Test
    fun `예약 불가 원인은 품질 상태, 보류, 수량 부족 순서로 판별한다`() {
        val defectiveAndHeld: Inventory =
            InventoryTestBuilder()
                .qualityStatus(QualityStatus.DEFECTIVE)
                .held("x", now)
                .quantity(0)
                .build()
        assertThrows<InventoryNotReservableException> { defectiveAndHeld.checkReservable(1) }

        val heldAndShort: Inventory = InventoryTestBuilder().held("x", now).quantity(0).build()
        assertThrows<AllocationHeldException> { heldAndShort.checkReservable(1) }

        assertThrows<InsufficientAvailableQuantityException> { inventory(quantity = 0).checkReservable(1) }
    }

    @Test
    fun `예약을 해제하면 예약 수량만 줄고 보류나 품질 상태와 무관하다`() {
        val inventory: Inventory =
            InventoryTestBuilder()
                .qualityStatus(
                    QualityStatus.DEFECTIVE,
                ).held("x", now)
                .quantity(10)
                .reservedQuantity(6)
                .build()

        inventory.release(4)

        assertEquals(2, inventory.reservedQuantity)
        assertEquals(10, inventory.quantity)
    }

    @Test
    fun `예약 수량보다 많이 해제하거나 출고하면 실패한다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 3)

        assertThrows<InsufficientReservedQuantityException> { inventory.release(4) }
        assertThrows<InsufficientReservedQuantityException> { inventory.ship(4) }
        assertEquals(3, inventory.reservedQuantity)
        assertInvalidQuantity { inventory.release(0) }
        assertInvalidQuantity { inventory.ship(0) }
    }

    @Test
    fun `출고 확정은 총 수량과 예약 수량을 함께 줄이고 0이 되어도 행은 유지된다`() {
        val inventory: Inventory = inventory(quantity = 10, reserved = 10)

        inventory.ship(10)

        assertEquals(0, inventory.quantity)
        assertEquals(0, inventory.reservedQuantity)
        assertEquals(0, inventory.availableQuantity)
    }

    @Test
    fun `보류하면 사유와 시각을 기록하고 이미 보류 중이면 처음 값을 유지한다`() {
        val inventory: Inventory = inventory()

        assertTrue(inventory.hold("Lot 부족 보고", now))
        assertFalse(inventory.hold("다른 사유", now.plusHours(1)))

        assertTrue(inventory.allocationHold)
        assertEquals("Lot 부족 보고", inventory.holdReason)
        assertEquals(now, inventory.heldAt)
    }

    @Test
    fun `보류 사유가 비어 있거나 100자를 넘으면 실패`() {
        listOf(null, "", "  ", "a".repeat(101)).forEach { reason: String? ->
            val e: InvalidDomainValueException = assertThrows { inventory().hold(reason, now) }
            assertEquals(InventoryErrorCode.INVALID_HOLD_REASON, e.errorCode)
        }
        inventory().hold("a".repeat(100), now)
    }

    @Test
    fun `보류를 풀면 사유와 시각이 지워지고 보류가 아니었으면 false`() {
        val inventory: Inventory = InventoryTestBuilder().held("부족", now).build()

        assertTrue(inventory.releaseHold())
        assertFalse(inventory.allocationHold)
        assertNull(inventory.holdReason)
        assertNull(inventory.heldAt)
        assertFalse(inventory.releaseHold())
    }

    @Test
    fun `복원한 재고는 저장된 값을 그대로 가지고 식별자로 동등성을 판단한다`() {
        val a: Inventory = InventoryTestBuilder().quantity(5).build()

        assertEquals(5, a.quantity)
        assertEquals(a, InventoryTestBuilder().quantity(99).build())
    }

    @Test
    fun `무작위 연산을 반복해도 수량 불변식이 유지된다`() {
        val random: Random = Random(7)
        repeat(200) {
            val inventory: Inventory = inventory(quantity = random.nextInt(0, 20), reserved = 0)
            repeat(100) {
                val amount: Int = random.nextInt(1, 8)
                runCatching {
                    when (random.nextInt(5)) {
                        0 -> inventory.increase(amount)
                        1 -> inventory.decrease(amount)
                        2 -> inventory.reserve(amount)
                        3 -> inventory.release(amount)
                        else -> inventory.ship(amount)
                    }
                }
                assertTrue(inventory.quantity >= 0, "총 수량 ≥ 0")
                assertTrue(inventory.reservedQuantity >= 0, "예약 수량 ≥ 0")
                assertTrue(inventory.reservedQuantity <= inventory.quantity, "예약 수량 ≤ 총 수량")
            }
        }
    }
}
