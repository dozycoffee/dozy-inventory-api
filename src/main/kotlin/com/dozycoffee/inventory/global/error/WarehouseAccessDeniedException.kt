package com.dozycoffee.inventory.global.error

/** 창고 관리자가 배정되지 않은 창고(또는 소속 사본이 없어 어느 창고도)에 접근했다. 창고별 접근을 검사하는 모든 도메인이 같은 오류로 응답한다 */
class WarehouseAccessDeniedException : DomainException(CommonErrorCode.WAREHOUSE_ACCESS_DENIED)
