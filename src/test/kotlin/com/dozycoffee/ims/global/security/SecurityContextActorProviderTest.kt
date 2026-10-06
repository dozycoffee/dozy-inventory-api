package com.dozycoffee.ims.global.security

import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import reactor.core.publisher.Mono

class SecurityContextActorProviderTest {
    private val provider: SecurityContextActorProvider = SecurityContextActorProvider()

    @Test
    fun `보안 컨텍스트가 없으면 요청 밖 작업이므로 SystemActor이다`() =
        runBlocking<Unit> {
            assertThat(provider.get()).isEqualTo(SystemActor)
        }

    @Test
    fun `Auth 토큰이 아닌 인증이 컨텍스트에 있으면 실패한다`() {
        val result: Mono<Actor> =
            mono { provider.get() }
                .contextWrite(
                    ReactiveSecurityContextHolder.withSecurityContext(Mono.just(SecurityContextImpl(TestingAuthenticationToken("u", "p")))),
                )

        assertThrows<IllegalStateException> { result.block() }
    }
}
