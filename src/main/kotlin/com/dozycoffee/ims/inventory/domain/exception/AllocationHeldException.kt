package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class AllocationHeldException : DomainException(InventoryErrorCode.ALLOCATION_HELD)
