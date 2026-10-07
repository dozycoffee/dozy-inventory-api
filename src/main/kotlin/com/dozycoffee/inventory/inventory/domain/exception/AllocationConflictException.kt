package com.dozycoffee.inventory.inventory.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** 계획을 세운 뒤 확정하는 사이 다른 요청이 같은 행을 가져가 할당하지 못했다. 새 트랜잭션에서 다시 시도하면 성공할 수 있다 */
class AllocationConflictException : DomainException(InventoryErrorCode.ALLOCATION_CONFLICT)
