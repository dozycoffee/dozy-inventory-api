package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class LotNotFoundException : DomainException(InventoryErrorCode.LOT_NOT_FOUND)
