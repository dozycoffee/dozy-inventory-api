package com.dozycoffee.inventory.global.security

/**
 * Auth에 `inventory:{code}`로 등록하는 role 코드. 등록 후 code는 바꿀 수 없다.
 * role은 기능 인가만 맡고, 창고별 접근 범위는 role이 아니라 `warehouse_access` 사본으로 판단한다.
 */
enum class InventoryRole(
    val code: String,
) {
    /** 서비스 간 호출(WMS, OMS, Store)에 쓰는 system client */
    SERVICE("service"),

    /** 창고 관리자. 배정된 창고만 다룬다 */
    WAREHOUSE_MANAGER("warehouse_manager"),

    /** 본사 관리자. 전체 창고와 마스터 관리·승인을 다룬다 */
    ADMIN("admin"),

    /** 원자재(상품 마스터) 담당. 상품 등록·상태 변경과 상품 조회만 하고 재고는 보지 못한다 */
    MATERIAL_MANAGER("material_manager"),
}

/** `@PreAuthorize`에 쓰는 SpEL 식. 어노테이션 인자는 컴파일 타임 상수여야 해서 문자열로 둔다 */
object InventoryAuthorize {
    const val ANY: String = "hasAnyRole('service','warehouse_manager','admin')"
    const val SERVICE: String = "hasRole('service')"
    const val SERVICE_OR_ADMIN: String = "hasAnyRole('service','admin')"
    const val ADMIN: String = "hasRole('admin')"
    const val ADMIN_OR_WAREHOUSE_MANAGER: String = "hasAnyRole('admin','warehouse_manager')"

    /** 상품 마스터 쓰기. `ADMIN`은 모든 마스터 권한을 포함한다 */
    const val PRODUCT_WRITE: String = "hasAnyRole('admin','material_manager')"

    /** 상품 조회. 재고를 보는 `ANY`에 원자재 담당만 더했다 */
    const val PRODUCT_READ: String = "hasAnyRole('service','warehouse_manager','admin','material_manager')"
}
