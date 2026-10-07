package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class DuplicateIdempotencyKeyException : DomainException(InventoryErrorCode.DUPLICATE_IDEMPOTENCY_KEY)
