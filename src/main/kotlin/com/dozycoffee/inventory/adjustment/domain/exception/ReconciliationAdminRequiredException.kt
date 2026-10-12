package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 변동량이 승인 임계치를 넘는 보정안은 본사 관리자(상위 승인자)만 승인할 수 있다 */
class ReconciliationAdminRequiredException : DomainException(AdjustmentErrorCode.RECONCILIATION_ADMIN_REQUIRED)
