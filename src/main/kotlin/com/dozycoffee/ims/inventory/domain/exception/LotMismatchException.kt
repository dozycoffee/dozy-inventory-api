package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class LotMismatchException : DomainException(InventoryErrorCode.LOT_MISMATCH)
