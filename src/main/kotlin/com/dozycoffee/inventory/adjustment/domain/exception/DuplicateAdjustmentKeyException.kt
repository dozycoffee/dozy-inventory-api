package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class DuplicateAdjustmentKeyException : DomainException(AdjustmentErrorCode.DUPLICATE_ADJUSTMENT_KEY)
