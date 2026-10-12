package com.dozycoffee.inventory.access.application.port.out

import com.dozycoffee.inventory.access.domain.model.WarehouseAccess
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId

/** 직원의 창고 소속 사본. 쓰기는 호출한 서비스의 트랜잭션 안에서 실행한다 */
interface WarehouseAccessRepository {
    /** 직원의 소속 창고 ID. 사본이 없으면 빈 집합 */
    suspend fun findWarehouseIds(principalId: PrincipalId): Set<Long>

    /** 직원의 소속을 [access]의 창고 집합으로 교체한다. 없는 창고는 지우고 새 창고는 추가하며 이미 있는 창고는 수신 시각만 갱신한다 */
    suspend fun replace(access: WarehouseAccess)
}
