package com.dozycoffee.inventory.adjustment.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

/** WMS에서 창고 수량을 조회하지 못했다(연결 실패, 시간 초과, 오류 응답, 계약에 맞지 않는 응답) */
class WmsUnavailableException(
    cause: Throwable? = null,
) : DomainException(AdjustmentErrorCode.WMS_UNAVAILABLE) {
    init {
        if (cause != null) initCause(cause)
    }
}
