package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class ApprovalRequiredException : DomainException(AdjustmentErrorCode.APPROVAL_REQUIRED)
