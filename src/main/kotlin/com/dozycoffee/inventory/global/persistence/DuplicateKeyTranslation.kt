package com.dozycoffee.inventory.global.persistence

import com.dozycoffee.inventory.global.error.BusinessException
import io.r2dbc.spi.R2dbcException
import org.springframework.dao.DataIntegrityViolationException

private const val MYSQL_ER_DUP_ENTRY: Int = 1062

/**
 * MySQL 유니크 위반은 Spring이 DuplicateKeyException이 아닌 상위 타입으로 번역하고, FK·NOT NULL·CHECK 위반도 같은 타입이다.
 * 중복 충돌만 골라내려면 원인 R2dbcException의 벤더 코드를 확인해야 한다.
 */
fun DataIntegrityViolationException.isDuplicateKey(): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<R2dbcException>()
        .any { it.errorCode == MYSQL_ER_DUP_ENTRY }

/** 유니크 제약 위반만 [duplicate]가 만든 도메인 예외로 바꾸고, 그 외 무결성 위반은 그대로 던진다 */
suspend inline fun <T> translatingDuplicateKey(
    duplicate: () -> BusinessException,
    block: () -> T,
): T {
    try {
        return block()
    } catch (e: DataIntegrityViolationException) {
        if (e.isDuplicateKey()) throw duplicate()
        throw e
    }
}
