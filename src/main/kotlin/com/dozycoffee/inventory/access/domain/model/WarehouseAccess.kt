package com.dozycoffee.inventory.access.domain.model

import com.dozycoffee.inventory.access.domain.exception.AccessErrorCode
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.global.error.InvalidDomainValueException

/**
 * 한 직원의 창고 소속 전체. WMS가 소유하는 사실의 사본이라 이벤트가 오면 통째로 교체한다(ADR-0032).
 * 불변식: 창고 ID는 양수이고 중복이 없으며 [MAX_WAREHOUSES]개를 넘지 않는다. 빈 집합은 소속이 없다는 뜻이다.
 */
class WarehouseAccess private constructor(
    val principalId: PrincipalId,
    val warehouseIds: Set<Long>,
) {
    override fun equals(other: Any?): Boolean = other is WarehouseAccess && principalId == other.principalId

    override fun hashCode(): Int = principalId.hashCode()

    companion object {
        const val MAX_WAREHOUSES: Int = 1000

        /** [warehouseIds]에 중복이 있으면 입력 오류이므로 목록을 받아 검사한다 */
        fun of(
            principalId: PrincipalId,
            warehouseIds: List<Long>,
        ): WarehouseAccess {
            if (warehouseIds.size > MAX_WAREHOUSES || warehouseIds.any { it < 1 } || warehouseIds.toSet().size != warehouseIds.size) {
                throw InvalidDomainValueException(AccessErrorCode.INVALID_WAREHOUSE_ACCESS)
            }
            return WarehouseAccess(principalId, warehouseIds.toSet())
        }
    }
}
