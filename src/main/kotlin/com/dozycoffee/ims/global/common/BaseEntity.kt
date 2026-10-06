package com.dozycoffee.ims.global.common

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

abstract class BaseEntity {
    @CreatedDate
    var createdAt: LocalDateTime? = null
        private set

    @CreatedBy
    var createdBy: String? = null
        private set

    @LastModifiedDate
    var updatedAt: LocalDateTime? = null
        private set

    @LastModifiedBy
    var updatedBy: String? = null
        private set

    /**
     * 도메인 모델에서 재구성한 엔티티는 createdAt/createdBy를 들고 있지 않아, 갱신 시 Auditing이 채우지 않으면 NULL로 덮어쓴다.
     * 저장 전에 기존 엔티티에서 복사해 보존한다. updatedAt/updatedBy는 저장할 때마다 Auditing이 갱신한다.
     */
    open fun copyAuditFieldsFrom(existing: BaseEntity) {
        this.createdAt = existing.createdAt
        this.createdBy = existing.createdBy
    }
}
