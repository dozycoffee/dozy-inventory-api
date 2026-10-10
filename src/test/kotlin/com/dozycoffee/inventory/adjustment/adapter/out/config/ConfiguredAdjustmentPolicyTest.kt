package com.dozycoffee.inventory.adjustment.adapter.out.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfiguredAdjustmentPolicyTest {
    private val policy = ConfiguredAdjustmentPolicy(AdjustmentProperties(AdjustmentProperties.ApprovalThreshold(100, mapOf("BEAN" to 20))))

    @Test
    fun `설정된 카테고리는 그 임계치를 쓰고 그 외 카테고리는 기본값을 쓴다`() {
        assertEquals(20, policy.approvalThresholdFor("BEAN"))
        assertEquals(100, policy.approvalThresholdFor("SYRUP"))
    }
}
