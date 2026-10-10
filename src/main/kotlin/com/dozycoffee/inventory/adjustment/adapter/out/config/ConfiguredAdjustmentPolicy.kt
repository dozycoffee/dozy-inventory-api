package com.dozycoffee.inventory.adjustment.adapter.out.config

import com.dozycoffee.inventory.adjustment.application.port.out.AdjustmentPolicy
import org.springframework.stereotype.Component

/** 설정(`inventory.adjustment.approval-threshold`)에서 읽은 값으로 정책을 정한다 */
@Component
class ConfiguredAdjustmentPolicy(
    private val properties: AdjustmentProperties,
) : AdjustmentPolicy {
    override fun approvalThresholdFor(category: String): Int = properties.approvalThreshold.forCategory(category)
}
