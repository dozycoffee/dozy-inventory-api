package com.dozycoffee.inventory.global.error

open class ApplicationException protected constructor(
    errorCode: ErrorCode,
) : BusinessException(errorCode)
