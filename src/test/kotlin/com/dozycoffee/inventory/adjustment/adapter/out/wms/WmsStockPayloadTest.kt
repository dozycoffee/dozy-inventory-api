package com.dozycoffee.inventory.adjustment.adapter.out.wms

import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.global.domain.QualityStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WmsStockPayloadTest {
    private fun item(
        productId: Long? = 100L,
        lotNumber: String? = "LOT-A",
        qualityStatus: String? = "NORMAL",
        quantity: Int? = 5,
    ): WmsStockPayload.Item = WmsStockPayload.Item(productId, lotNumber, qualityStatus, quantity)

    @Test
    fun `항목을 수량 객체로 바꾼다`() {
        val quantities: List<WmsStockQuantity> =
            WmsStockPayload(listOf(item(), item(101L, "LOT-B", "DISPOSAL_SCHEDULED", 0))).toQuantities()

        assertEquals(
            listOf(
                WmsStockQuantity(100L, "LOT-A", QualityStatus.NORMAL, 5),
                WmsStockQuantity(101L, "LOT-B", QualityStatus.DISPOSAL_SCHEDULED, 0),
            ),
            quantities,
        )
        assertEquals(emptyList<WmsStockQuantity>(), WmsStockPayload(emptyList()).toQuantities())
    }

    @Test
    fun `계약에 맞지 않으면 IllegalArgumentException이다`() {
        listOf(
            WmsStockPayload(null),
            WmsStockPayload(listOf(item(productId = null))),
            WmsStockPayload(listOf(item(productId = 0L))),
            WmsStockPayload(listOf(item(lotNumber = null))),
            WmsStockPayload(listOf(item(lotNumber = " "))),
            WmsStockPayload(listOf(item(qualityStatus = null))),
            WmsStockPayload(listOf(item(qualityStatus = "UNKNOWN"))),
            WmsStockPayload(listOf(item(quantity = null))),
            WmsStockPayload(listOf(item(quantity = -1))),
        ).forEach { payload -> assertThrows<IllegalArgumentException> { payload.toQuantities() } }
    }
}
