package com.dozycoffee.ims.global.error

open class DomainException protected constructor(
    errorCode: ErrorCode,
) : BusinessException(errorCode)
