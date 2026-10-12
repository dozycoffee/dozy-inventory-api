package com.dozycoffee.inventory.global.domain

import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException

/**
 * 요청자가 접근할 수 있는 창고의 범위(ADR-0032). 본사 관리자와 서비스는 [All], 창고 관리자는 배정된 창고만 담은 [Only]이다.
 * 창고별로 데이터를 보여 주는 조회가 이 범위를 받아 적용한다.
 */
sealed interface WarehouseScope {
    /** 모든 창고 */
    data object All : WarehouseScope

    /** [warehouseIds]에 있는 창고만. 비어 있으면 어느 창고에도 접근할 수 없다 */
    data class Only(
        val warehouseIds: Set<Long>,
    ) : WarehouseScope

    fun allows(warehouseId: Long): Boolean =
        when (this) {
            All -> true
            is Only -> warehouseId in warehouseIds
        }

    /** 접근할 수 없는 창고이면 [WarehouseAccessDeniedException]이다 */
    fun require(warehouseId: Long) {
        if (!allows(warehouseId)) throw WarehouseAccessDeniedException()
    }

    /**
     * 요청한 창고 ID를 범위 안으로 좁힌다. [requested]가 null(창고 생략)이면 [All]은 null(제한 없음), [Only]는 배정된 모든 창고를 돌려준다.
     * 지정했다면 범위 밖의 창고가 하나라도 있을 때 [WarehouseAccessDeniedException]이고, 아니면 그대로 돌려준다.
     */
    fun narrow(requested: Set<Long>?): Set<Long>? =
        when {
            requested == null -> (this as? Only)?.warehouseIds
            else -> requested.also { ids -> ids.forEach(::require) }
        }
}
