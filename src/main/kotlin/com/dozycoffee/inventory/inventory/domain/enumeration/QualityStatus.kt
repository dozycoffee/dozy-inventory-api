package com.dozycoffee.inventory.inventory.domain.enumeration

enum class QualityStatus {
    NORMAL,
    DEFECTIVE,
    DISPOSAL_SCHEDULED,
    ;

    /** WMS 검수가 판정할 수 있는 품질 상태. 폐기 예정은 IMS의 유통기한 스캔이 정한다 */
    val isInspectionResult: Boolean
        get() = this == NORMAL || this == DEFECTIVE
}
