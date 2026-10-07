package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class DuplicateLotException : DomainException(InventoryErrorCode.DUPLICATE_LOT)
