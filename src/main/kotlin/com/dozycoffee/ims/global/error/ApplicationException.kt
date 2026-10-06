package com.dozycoffee.ims.global.error

open class ApplicationException protected constructor(
    errorCode: ErrorCode,
) : BusinessException(errorCode)
