package com.dozycoffee.inventory.global.security

import com.dozycoffee.auth.starter.DozyAuthenticationToken
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.context.annotation.Profile
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 요청의 보안 컨텍스트(Reactor Context)에서 행위자를 꺼낸다. 보안 컨텍스트가 없으면 요청 밖에서 실행되는
 * 작업(스케줄러 등)이므로 [SystemActor]다. 컨텍스트는 있는데 Auth 토큰이 아니면 행위자를 특정할 수 없으므로 실패시킨다.
 * system client 토큰에는 클라이언트 이름이 없어 직원과 같이 `principalId`(UUID)가 [UserActor]에 담긴다.
 */
@Component
@Profile("!local")
class SecurityContextActorProvider : CurrentActorProvider {
    override suspend fun get(): Actor {
        val authentication = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication ?: return SystemActor
        check(authentication is DozyAuthenticationToken) { "Auth 토큰으로 인증되지 않은 요청입니다." }
        val principal = authentication.authenticatedPrincipal
        return UserActor(principal.key.id, principal.roles)
    }
}
