package com.dozycoffee.ims.global.common

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import java.time.LocalDateTime

/** 생성 정보(`created_at`, `created_by`)만 가진 엔티티. 만든 뒤 바뀌지 않는 원장성 테이블이 상속한다 */
abstract class CreatedAuditEntity {
    @CreatedDate
    var createdAt: LocalDateTime? = null
        private set

    @CreatedBy
    var createdBy: String? = null
        private set

    protected fun copyCreatedFieldsFrom(existing: CreatedAuditEntity) {
        this.createdAt = existing.createdAt
        this.createdBy = existing.createdBy
    }
}
