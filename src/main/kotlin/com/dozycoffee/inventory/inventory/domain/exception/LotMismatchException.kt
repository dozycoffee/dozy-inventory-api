package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class LotMismatchException : DomainException(InventoryErrorCode.LOT_MISMATCH)
