package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class IdempotencyKeyConflictException : DomainException(InventoryErrorCode.IDEMPOTENCY_KEY_CONFLICT)
