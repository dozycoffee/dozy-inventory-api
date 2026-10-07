package com.dozycoffee.inventory.global.common

import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.time.LocalDateTime

/** 생성 정보에 수정 정보(`updated_at`, `updated_by`)를 더한 엔티티. 대부분의 테이블이 상속한다 */
abstract class BaseEntity : CreatedAuditEntity() {
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
        copyCreatedFieldsFrom(existing)
    }
}
