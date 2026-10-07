package com.dozycoffee.inventory.global.error

/** 이미 처리한 멱등 키가 다른 내용으로 다시 요청되었다. 수량을 바꾸는 모든 도메인이 같은 오류로 응답한다 */
class IdempotencyKeyConflictException : DomainException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
