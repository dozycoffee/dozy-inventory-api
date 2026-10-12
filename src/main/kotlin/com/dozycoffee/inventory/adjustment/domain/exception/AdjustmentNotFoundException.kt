package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class AdjustmentNotFoundException : DomainException(AdjustmentErrorCode.ADJUSTMENT_NOT_FOUND)
