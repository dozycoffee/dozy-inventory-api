package com.dozycoffee.inventory.global.security

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.withContext
import reactor.util.context.Context
import kotlin.coroutines.coroutineContext

/**
 * [Actor]를 Reactor Context에 실어 전달한다. Spring Data Auditing이 호출하는 [CurrentActorProvider]가
 * 같은 Reactor Context를 통해 작업의 주체를 알 수 있다. 요청 밖 작업(스케줄러)은 [SystemActor]로 감싸서 실행한다.
 */
object ActorContext {
    private const val KEY: String = "com.dozycoffee.inventory.actor"

    suspend fun <T> with(
        actor: Actor,
        block: suspend () -> T,
    ): T {
        val current: Context = coroutineContext[ReactorContext]?.context ?: Context.empty()
        return withContext(ReactorContext(current.put(KEY, actor))) { block() }
    }

    suspend fun current(): Actor? =
        coroutineContext[ReactorContext]
            ?.context
            ?.getOrEmpty<Actor>(KEY)
            ?.orElse(null)
}
