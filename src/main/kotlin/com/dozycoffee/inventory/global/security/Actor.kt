package com.dozycoffee.inventory.global.security

import java.util.UUID

sealed interface Actor {
    /** 감사 컬럼(`created_by` 등)에 기록하는 값 */
    val auditName: String
}

/** 인증된 사용자. [roles]는 inventory audience의 role 코드(prefix 제거)다 */
data class UserActor(
    val principalId: UUID,
    val roles: Set<String>,
) : Actor {
    override val auditName: String get() = principalId.toString()
}

/** 요청과 무관하게 실행되는 작업(스케줄러 등) */
object SystemActor : Actor {
    override val auditName: String = "system"
}
