package com.dozycoffee.inventory.global.domain

enum class QualityStatus {
    NORMAL,
    DEFECTIVE,
    DISPOSAL_SCHEDULED,
    ;

    /** WMS 검수가 판정할 수 있는 품질 상태. 폐기 예정은 inventory 서비스의 유통기한 스캔이 정한다 */
    val isInspectionResult: Boolean
        get() = this == NORMAL || this == DEFECTIVE

    /** 폐기할 수 있는 품질 상태. 정상 재고는 폐기하지 않고 불량이거나 폐기 예정으로 옮겨진 재고만 폐기한다 */
    val isDisposable: Boolean
        get() = this == DEFECTIVE || this == DISPOSAL_SCHEDULED
}
