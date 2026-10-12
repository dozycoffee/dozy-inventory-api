package com.dozycoffee.inventory.global.error

import org.springframework.http.HttpStatus

object ErrorTypeHttpStatusMapper {
    fun resolve(errorType: ErrorType): HttpStatus =
        when (errorType) {
            ErrorType.VALIDATION -> HttpStatus.BAD_REQUEST
            ErrorType.FORBIDDEN -> HttpStatus.FORBIDDEN
            ErrorType.NOT_FOUND -> HttpStatus.NOT_FOUND
            ErrorType.CONFLICT -> HttpStatus.CONFLICT
            ErrorType.UPSTREAM -> HttpStatus.BAD_GATEWAY
            ErrorType.INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR
        }
}
