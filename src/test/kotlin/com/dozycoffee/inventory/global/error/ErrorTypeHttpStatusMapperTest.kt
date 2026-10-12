package com.dozycoffee.inventory.global.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorTypeHttpStatusMapperTest {
    @Test
    fun `오류 유형마다 HTTP 상태를 정한다`() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorTypeHttpStatusMapper.resolve(ErrorType.VALIDATION))
        assertEquals(HttpStatus.FORBIDDEN, ErrorTypeHttpStatusMapper.resolve(ErrorType.FORBIDDEN))
        assertEquals(HttpStatus.NOT_FOUND, ErrorTypeHttpStatusMapper.resolve(ErrorType.NOT_FOUND))
        assertEquals(HttpStatus.CONFLICT, ErrorTypeHttpStatusMapper.resolve(ErrorType.CONFLICT))
        assertEquals(HttpStatus.BAD_GATEWAY, ErrorTypeHttpStatusMapper.resolve(ErrorType.UPSTREAM))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorTypeHttpStatusMapper.resolve(ErrorType.INTERNAL))
    }

    @Test
    fun `모든 오류 유형이 상태를 가진다`() {
        ErrorType.entries.forEach { ErrorTypeHttpStatusMapper.resolve(it) }
    }
}
