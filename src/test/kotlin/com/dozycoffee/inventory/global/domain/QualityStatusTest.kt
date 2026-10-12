package com.dozycoffee.inventory.global.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QualityStatusTest {
    @Test
    fun `WMS 검수가 판정할 수 있는 것은 정상과 불량이고 폐기 예정은 아니다`() {
        assertTrue(QualityStatus.NORMAL.isInspectionResult)
        assertTrue(QualityStatus.DEFECTIVE.isInspectionResult)
        assertFalse(QualityStatus.DISPOSAL_SCHEDULED.isInspectionResult)
    }

    @Test
    fun `폐기할 수 있는 것은 불량과 폐기 예정이고 정상은 아니다`() {
        assertFalse(QualityStatus.NORMAL.isDisposable)
        assertTrue(QualityStatus.DEFECTIVE.isDisposable)
        assertTrue(QualityStatus.DISPOSAL_SCHEDULED.isDisposable)
    }
}
