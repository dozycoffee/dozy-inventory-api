package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class InventoryNotReservableException : DomainException(InventoryErrorCode.INVENTORY_NOT_RESERVABLE)
