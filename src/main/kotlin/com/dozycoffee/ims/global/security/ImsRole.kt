package com.dozycoffee.ims.global.security

/**
 * Auth에 `ims:{code}`로 등록하는 role 코드. 등록 후 code는 바꿀 수 없다.
 * role은 기능 인가만 맡고, 창고별 접근 범위는 role이 아니라 `warehouse_access` 사본으로 판단한다.
 */
enum class ImsRole(
    val code: String,
) {
    /** 서비스 간 호출(WMS, OMS, Store)에 쓰는 system client */
    SERVICE("service"),

    /** 창고 관리자. 배정된 창고만 다룬다 */
    WAREHOUSE_MANAGER("warehouse_manager"),

    /** 본사 관리자. 전체 창고와 마스터 관리·승인을 다룬다 */
    ADMIN("admin"),
}

/** `@PreAuthorize`에 쓰는 SpEL 식. 어노테이션 인자는 컴파일 타임 상수여야 해서 문자열로 둔다 */
object ImsAuthorize {
    const val ANY: String = "hasAnyRole('service','warehouse_manager','admin')"
    const val ADMIN: String = "hasRole('admin')"
}
