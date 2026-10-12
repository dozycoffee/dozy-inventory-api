package com.dozycoffee.inventory.inventory.application.port.`in`

interface FindWarehouseIdsUseCase {
    /** 재고 행이 있는 창고 ID를 오름차순으로 반환한다. 대사 대상 창고를 정하는 데 쓴다 */
    suspend fun findWarehouseIds(): List<Long>
}
