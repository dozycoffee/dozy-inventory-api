package com.dozycoffee.inventory.adjustment.application.port.`in`.command

import com.dozycoffee.inventory.global.domain.WarehouseScope

/**
 * 대사 보정안의 승인·거부 요청. [reviewer]는 요청자(토큰의 principalId)이고 승인자로 기록된다. [isAdmin]은 본사 관리자 여부이며
 * 변동량이 임계치를 넘는 보정안은 본사 관리자만 승인할 수 있다. [scope]는 요청자가 접근할 수 있는 창고로, 범위 밖 창고의 보정안은 거부한다(ADR-0032, ADR-0033).
 */
data class ReviewReconciliationCommand(
    val stockAdjustmentId: Long,
    val reviewer: String,
    val isAdmin: Boolean,
    val scope: WarehouseScope,
)
