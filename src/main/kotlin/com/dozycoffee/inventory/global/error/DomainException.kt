package com.dozycoffee.inventory.global.error

open class DomainException protected constructor(
    errorCode: ErrorCode,
) : BusinessException(errorCode)
