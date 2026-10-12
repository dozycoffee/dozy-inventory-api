package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 조정(보정안)의 현재 상태에서는 할 수 없는 전이를 요청했다(예: 거부된 보정안을 승인) */
class InvalidAdjustmentStateException : DomainException(AdjustmentErrorCode.INVALID_ADJUSTMENT_STATE)
