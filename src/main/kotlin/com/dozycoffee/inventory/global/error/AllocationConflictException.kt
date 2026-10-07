package com.dozycoffee.inventory.global.error

/**
 * 재고 할당을 계획한 뒤 확정하는 사이 다른 요청이 같은 행을 가져가 할당하지 못했다. 새 트랜잭션에서 다시 시도하면 성공할 수 있다.
 * 할당을 호출하는 도메인이 재시도 대상으로 구분해야 해서 도메인 간에 공유한다.
 */
class AllocationConflictException : DomainException(CommonErrorCode.ALLOCATION_CONFLICT)
