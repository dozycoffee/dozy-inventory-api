package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class AllocationHeldException : DomainException(InventoryErrorCode.ALLOCATION_HELD)
