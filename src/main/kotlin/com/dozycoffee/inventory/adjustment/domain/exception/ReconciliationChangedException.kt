package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 보정안을 만든 뒤 수량이 바뀌어 보정안을 새 수량으로 다시 계산했다. 아무것도 반영하지 않았고 담당자가 새 숫자를 확인하고 다시 승인해야 한다 */
class ReconciliationChangedException : DomainException(AdjustmentErrorCode.RECONCILIATION_CHANGED)
