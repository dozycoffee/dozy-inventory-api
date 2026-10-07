package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class IdempotencyKeyConflictException : DomainException(InventoryErrorCode.IDEMPOTENCY_KEY_CONFLICT)
