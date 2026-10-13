package com.dozycoffee.inventory.global.domain

import java.security.SecureRandom

/**
 * UUID v7(RFC 9562) 생성기. 상위 48비트는 밀리초 시각이라 생성 순서대로 정렬되고 나머지는 무작위다.
 * 시각은 호출한 쪽이 `Clock`에서 가져와 넘긴다(같은 밀리초 안의 순서는 보장하지 않는다).
 */
object UuidV7 {
    private val random: SecureRandom = SecureRandom()

    fun generate(epochMillis: Long): String {
        require(epochMillis in 0..MAX_MILLIS) { "UUID v7의 시각은 48비트 밀리초여야 한다: $epochMillis" }
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        for (i in 0 until 6) bytes[i] = (epochMillis shr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[7] = bytes[7]
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex: String = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private const val MAX_MILLIS: Long = (1L shl 48) - 1
}
