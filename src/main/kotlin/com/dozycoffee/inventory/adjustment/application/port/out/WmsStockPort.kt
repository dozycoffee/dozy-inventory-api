package com.dozycoffee.inventory.adjustment.application.port.out

import com.dozycoffee.inventory.global.domain.QualityStatus

/** WMS가 알려 주는 창고의 Lot별 품질 상태별 실제 수량(ADR-0033). WMS가 기준인 물리 사실이며 예약과 무관한 총 수량이다 */
data class WmsStockQuantity(
    val productId: Long,
    val lotNumber: String,
    val qualityStatus: QualityStatus,
    val quantity: Int,
)

/** WMS의 창고 수량 조회. 대사 배치와 승인 시점 재확인이 쓴다 */
interface WmsStockPort {
    /**
     * 창고의 수량을 조회한다. [productId]와 [lotNumber]를 주면 그 조건의 항목만 준다. 수량이 0인 항목은 없을 수 있다.
     * 조회하지 못하면 [com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException]이다.
     */
    suspend fun findQuantities(
        warehouseId: Long,
        productId: Long?,
        lotNumber: String?,
    ): List<WmsStockQuantity>
}
