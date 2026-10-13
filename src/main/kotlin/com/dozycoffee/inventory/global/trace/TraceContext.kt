package com.dozycoffee.inventory.global.trace

import kotlinx.coroutines.reactor.ReactorContext
import reactor.util.context.Context
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext

/**
 * 요청의 W3C 추적 컨텍스트(`traceparent`)를 Reactor Context에 실어 전달한다. 요청 필터가 [create]로 값을 만들어 컨텍스트에 쓰고,
 * 서비스 안쪽(Outbox 저장 등)은 [current]로 읽는다. 요청 밖 작업(스케줄러)에는 값이 없다.
 */
object TraceContext {
    const val KEY: String = "com.dozycoffee.inventory.traceparent"

    private val random: SecureRandom = SecureRandom()
    private val TRACEPARENT: Regex = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")
    private val HEX32: Regex = Regex("[0-9a-f]{32}")

    /**
     * 요청의 `traceparent`가 올바르면 같은 trace-id를 이어받고, 아니면 `X-Trace-Id`가 32자리 16진수일 때 그 값을, 그 외에는 새 값을
     * trace-id로 쓴다. span-id는 이 서비스의 것으로 새로 만든다. 모든 값이 0인 trace-id·span-id는 W3C 규격상 무효라 쓰지 않는다.
     */
    fun create(
        incomingTraceparent: String?,
        traceId: String?,
    ): String {
        val match: MatchResult? = incomingTraceparent?.let { TRACEPARENT.matchEntire(it) }
        val fromParent: String? = match?.groupValues?.get(1)?.takeUnless(::isZero)
        val fromTraceId: String? = traceId?.lowercase()?.takeIf { HEX32.matches(it) && !isZero(it) }
        val flags: String = match?.groupValues?.get(3) ?: "01"
        return "00-${fromParent ?: fromTraceId ?: randomHex(16)}-${randomHex(8)}-$flags"
    }

    fun contextOf(
        current: Context,
        traceparent: String,
    ): Context = current.put(KEY, traceparent)

    suspend fun current(): String? =
        coroutineContext[ReactorContext]
            ?.context
            ?.getOrEmpty<String>(KEY)
            ?.orElse(null)

    private fun isZero(hex: String): Boolean = hex.all { it == '0' }

    private fun randomHex(bytes: Int): String {
        while (true) {
            val value: String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            if (!isZero(value)) return value
        }
    }
}
