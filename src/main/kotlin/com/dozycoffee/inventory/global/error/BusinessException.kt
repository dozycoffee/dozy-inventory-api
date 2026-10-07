package com.dozycoffee.inventory.global.error

abstract class BusinessException protected constructor(
    val errorCode: ErrorCode,
) : RuntimeException(errorCode.message)
