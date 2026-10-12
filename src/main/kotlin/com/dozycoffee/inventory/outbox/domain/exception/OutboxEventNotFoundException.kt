package com.dozycoffee.inventory.outbox.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class OutboxEventNotFoundException : DomainException(OutboxErrorCode.OUTBOX_EVENT_NOT_FOUND)
