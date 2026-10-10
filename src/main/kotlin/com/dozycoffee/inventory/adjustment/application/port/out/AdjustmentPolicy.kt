package com.dozycoffee.inventory.adjustment.application.port.out

/** 조정의 업무 설정. 승인 임계치처럼 환경에 따라 달라지는 값을 포트로 받는다 */
fun interface AdjustmentPolicy {
    /** 상품 카테고리의 승인 임계치. 항목 하나의 변동량 절댓값이 이 값을 넘으면 승인자가 필요하다. 설정에 없는 카테고리는 기본값을 쓴다 */
    fun approvalThresholdFor(category: String): Int
}
