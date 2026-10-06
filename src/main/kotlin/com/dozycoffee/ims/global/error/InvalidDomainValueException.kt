package com.dozycoffee.ims.global.error

class InvalidDomainValueException(
    errorCode: ErrorCode,
) : DomainException(errorCode)
