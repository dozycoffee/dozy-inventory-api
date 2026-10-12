package com.dozycoffee.inventory.access.application.port.`in`

import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.security.Actor

interface ResolveWarehouseScopeUseCase {
    /**
     * 요청자가 접근할 수 있는 창고의 범위. `admin`·`service` role이나 요청 밖 작업이면 모든 창고이고, `warehouse_manager`만 가지면
     * 소속 사본의 창고만이다. 창고 관리자인데 사본이 없으면 로그를 남기고, 관리자 role이 없으면(또는 사본이 없으면)
     * [com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException]이다.
     */
    suspend fun resolve(actor: Actor): WarehouseScope
}
