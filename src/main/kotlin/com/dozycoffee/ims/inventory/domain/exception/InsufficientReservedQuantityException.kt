package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class InsufficientReservedQuantityException : DomainException(InventoryErrorCode.INSUFFICIENT_RESERVED_QUANTITY)
