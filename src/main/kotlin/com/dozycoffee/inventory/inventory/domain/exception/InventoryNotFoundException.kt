package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class InventoryNotFoundException : DomainException(InventoryErrorCode.INVENTORY_NOT_FOUND)
