package com.dozycoffee.ims.global.security

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ActorContextTest {
    private val provider: LocalActorProvider = LocalActorProvider()

    @Test
    fun `컨텍스트 밖에서는 현재 Actor가 없다`() =
        runBlocking<Unit> {
            assertThat(ActorContext.current()).isNull()
        }

    @Test
    fun `컨텍스트 안에서는 지정한 Actor를 얻는다`() =
        runBlocking<Unit> {
            val actor: Actor = ActorContext.with(SystemActor) { ActorContext.current()!! }

            assertThat(actor).isEqualTo(SystemActor)
        }

    @Test
    fun `안쪽 컨텍스트가 바깥 컨텍스트를 덮어쓰고 벗어나면 되돌아온다`() =
        runBlocking<Unit> {
            val user: UserActor = UserActor(UUID.randomUUID(), emptySet())

            val result: List<Actor?> =
                ActorContext.with(user) {
                    val outer: Actor? = ActorContext.current()
                    val inner: Actor? = ActorContext.with(SystemActor) { ActorContext.current() }
                    listOf(outer, inner, ActorContext.current())
                }

            assertThat(result).containsExactly(user, SystemActor, user)
        }

    @Test
    fun `Reactor 파이프라인 안의 코루틴에서도 Actor를 얻는다`() =
        runBlocking<Unit> {
            val actor: Actor =
                ActorContext.with(SystemActor) {
                    mono { provider.get() }.awaitSingle()
                }

            assertThat(actor).isEqualTo(SystemActor)
        }

    @Test
    fun `local Provider는 컨텍스트가 없으면 고정 개발 사용자를 반환한다`() =
        runBlocking<Unit> {
            assertThat(provider.get()).isEqualTo(LocalActorProvider.LOCAL_USER)
        }

    @Test
    fun `SystemActor와 UserActor의 감사 이름은 각각 system과 principalId이다`() {
        val principalId: UUID = UUID.randomUUID()

        assertThat(SystemActor.auditName).isEqualTo("system")
        assertThat(UserActor(principalId, emptySet()).auditName).isEqualTo(principalId.toString())
    }
}
