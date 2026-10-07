package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class InventoryNotReservableException : DomainException(InventoryErrorCode.INVENTORY_NOT_RESERVABLE)
