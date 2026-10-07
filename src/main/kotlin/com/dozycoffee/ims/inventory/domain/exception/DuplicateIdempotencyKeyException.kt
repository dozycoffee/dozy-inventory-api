package com.dozycoffee.ims.inventory.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class DuplicateIdempotencyKeyException : DomainException(InventoryErrorCode.DUPLICATE_IDEMPOTENCY_KEY)
