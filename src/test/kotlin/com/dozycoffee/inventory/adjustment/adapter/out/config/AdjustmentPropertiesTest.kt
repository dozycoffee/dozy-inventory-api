package com.dozycoffee.inventory.adjustment.adapter.out.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class AdjustmentPropertiesTest {
    private fun bind(vararg entries: Pair<String, String>): AdjustmentProperties =
        Binder(MapConfigurationPropertySource(entries.toMap()))
            .bind("inventory.adjustment", AdjustmentProperties::class.java)
            .orElseGet { AdjustmentProperties(AdjustmentProperties.ApprovalThreshold(100)) }

    @Test
    fun `아무 설정이 없어도 기본 임계치는 100이다`() {
        assertEquals(100, bind().approvalThreshold.default)
    }

    @Test
    fun `카테고리별 임계치가 없으면 기본값을 쓴다`() {
        val properties: AdjustmentProperties = bind("inventory.adjustment.approval-threshold.default" to "50")

        assertEquals(50, properties.approvalThreshold.forCategory("BEAN"))
    }

    @Test
    fun `맵에 있는 카테고리는 그 값을 쓰고 없는 카테고리는 기본값을 쓴다`() {
        val properties: AdjustmentProperties =
            bind(
                "inventory.adjustment.approval-threshold.default" to "100",
                "inventory.adjustment.approval-threshold.categories.BEAN" to "20",
            )

        assertEquals(20, properties.approvalThreshold.forCategory("BEAN"))
        assertEquals(100, properties.approvalThreshold.forCategory("SYRUP"))
    }

    @Test
    fun `임계치는 0 이상이어야 한다`() {
        assertThrows<IllegalArgumentException> { AdjustmentProperties.ApprovalThreshold(-1) }
        assertThrows<IllegalArgumentException> { AdjustmentProperties.ApprovalThreshold(10, mapOf("BEAN" to -1)) }
    }
}
