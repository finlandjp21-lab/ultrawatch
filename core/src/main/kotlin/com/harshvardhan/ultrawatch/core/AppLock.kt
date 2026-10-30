package com.harshvardhan.ultrawatch.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN lock for sensitive screens (messages, markets, SOS settings).
 * Stores only a salted, iterated SHA-256 hash. After 5 wrong tries the lock
 * pauses for 30 s, doubling each further wrong try (max 15 min).
 */
class AppLock(
    private var saltHex: String = "",
    private var hashHex: String = "",
    private var failures: Int = 0,
    private var lockedUntil: Long = 0,
) {
    val isSet get() = hashHex.isNotEmpty()

    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4-8 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        saltHex = salt.toHex()
        hashHex = hash(pin, salt)
        failures = 0; lockedUntil = 0
    }

    fun clear() { saltHex = ""; hashHex = ""; failures = 0; lockedUntil = 0 }

    fun lockoutRemainingMs(now: Long) = (lockedUntil - now).coerceAtLeast(0)

    sealed class Result {
        data object Ok : Result()
        data class Wrong(val triesLeft: Int) : Result()
        data class LockedOut(val ms: Long) : Result()
    }

    fun verify(pin: String, now: Long): Result {
        if (!isSet) return Result.Ok
        if (now < lockedUntil) return Result.LockedOut(lockedUntil - now)
        val ok = MessageDigest.isEqual(hash(pin, saltHex.fromHex()).toByteArray(), hashHex.toByteArray())
        if (ok) { failures = 0; return Result.Ok }
        failures++
        if (failures >= 5) {
            val extra = (failures - 5).coerceAtMost(5)
            val ms = (30_000L shl extra).coerceAtMost(15 * 60_000L)
            lockedUntil = now + ms
            return Result.LockedOut(ms)
        }
        return Result.Wrong(5 - failures)
    }

    fun encode() = "$saltHex|$hashHex|$failures|$lockedUntil"

    companion object {
        fun decode(s: String?): AppLock {
            val p = s?.split("|") ?: return AppLock()
            if (p.size != 4) return AppLock()
            return AppLock(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0)
        }

        private fun hash(pin: String, salt: ByteArray): String {
            var d = salt + pin.toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            repeat(10_000) { d = md.digest(d) }
            return d.toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
