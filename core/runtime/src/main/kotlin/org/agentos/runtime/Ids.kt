package org.agentos.runtime

import java.security.SecureRandom

/**
 * 会话、任务等的 ID：`<前缀>_<26 位 ULID>`（时间有序、全局唯一，不含可猜测的计数器）。
 * 例：`ses_01J9Z6Q7F3M2X8K4T0VB5N7C2D`。
 */
object Ids {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun session(now: Long = System.currentTimeMillis()) = "ses_" + ulid(now)

    fun task(now: Long = System.currentTimeMillis()) = "tsk_" + ulid(now)

    fun request(now: Long = System.currentTimeMillis()) = "req_" + ulid(now)

    fun ulid(now: Long): String {
        val chars = CharArray(26)
        var t = now
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        val bytes = ByteArray(10).also { random.nextBytes(it) }
        var bits = 0
        var acc = 0
        var pos = 10
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5 && pos < 26) {
                bits -= 5
                chars[pos++] = ALPHABET[(acc ushr bits) and 31]
            }
        }
        return String(chars)
    }
}
