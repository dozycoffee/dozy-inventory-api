package com.dozycoffee.inventory.global.error

class InvalidDomainValueException(
    errorCode: ErrorCode,
) : DomainException(errorCode)
