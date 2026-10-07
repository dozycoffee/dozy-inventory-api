package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class InsufficientAvailableQuantityException : DomainException(InventoryErrorCode.INSUFFICIENT_AVAILABLE_QUANTITY)
