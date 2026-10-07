package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class InsufficientAvailableQuantityException : DomainException(InventoryErrorCode.INSUFFICIENT_AVAILABLE_QUANTITY)
