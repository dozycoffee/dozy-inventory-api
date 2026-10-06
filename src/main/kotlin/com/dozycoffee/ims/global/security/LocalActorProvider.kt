package com.dozycoffee.ims.global.security

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/** 로컬 개발 전용. [ActorContext]에 주체가 있으면 그것을, 없으면 고정 개발 사용자를 쓴다 */
@Component
@Profile("local")
class LocalActorProvider : CurrentActorProvider {
    override suspend fun get(): Actor = ActorContext.current() ?: LOCAL_USER

    companion object {
        val LOCAL_PRINCIPAL_ID: UUID = UUID.fromString("00000000-0000-7000-8000-000000000001")
        val LOCAL_USER: UserActor = UserActor(LOCAL_PRINCIPAL_ID, ImsRole.entries.map { it.code }.toSet())
    }
}
