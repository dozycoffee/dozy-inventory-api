package com.dozycoffee.inventory.adjustment.adapter.out.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/** 조정 업무 설정. 승인 임계치의 기본값은 가정한 값이라 운영 전에 정한다(ADR-0026) */
@ConfigurationProperties("inventory.adjustment")
data class AdjustmentProperties(
    @DefaultValue val approvalThreshold: ApprovalThreshold,
) {
    /** 승인이 필요한 변동량의 기준. 맵에 없는 카테고리는 [default]를 쓴다. 0 이상이어야 하며 0이면 모든 변동에 승인이 필요하다 */
    data class ApprovalThreshold(
        @DefaultValue("100") val default: Int,
        val categories: Map<String, Int> = emptyMap(),
    ) {
        init {
            require(default >= 0 && categories.values.all { it >= 0 }) { "승인 임계치는 0 이상이어야 한다" }
        }

        fun forCategory(category: String): Int = categories[category] ?: default
    }
}
