package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class InsufficientReservedQuantityException : DomainException(InventoryErrorCode.INSUFFICIENT_RESERVED_QUANTITY)
